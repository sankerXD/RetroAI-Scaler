#include "gl_renderer.h"
#include "../common/log.h"
#include "../common/cpu_affinity.h"
#include <chrono>
#include <algorithm>
#include <cmath>
#include <vector>

namespace retroai {

// Vertex Shader for Fullscreen Quad
static const char* VERTEX_SHADER_SRC = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

// Fragment Shader: Edge-directed upscale + Retro Display FX.
//
// Geometry contract (all in screen pixels, top-left origin):
//   uSourceRect - the small 1x RetroArch window we sample from.
//   uOutputRect - where the enhanced image is painted.
// The source rect is ALWAYS punched out of the output so that our own
// output never re-enters the MediaProjection capture (feedback loop).
// Every fragment outside the output rect is discarded, leaving the
// cleared transparent framebuffer -> the overlay can never black out
// the device.
static const char* AI_SCALER_FRAG_SRC = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

out vec4 fragColor;

uniform samplerExternalOES uExternalTex;
uniform vec2 uScreenSize;
uniform vec2 uCaptureSize;
uniform vec4 uSourceRect;  // x, y, w, h
uniform vec4 uOutputRect;  // x, y, w, h
uniform vec2 uNativeRes;   // console native resolution, e.g. 240x160
uniform float uAiScale;    // ESPCN reconstruction factor: uYHiTex is uNativeRes * this
uniform int uProtectSource; // 1 = never paint over the capture window
uniform int uNeuralRgb;     // 1 = uYHiTex holds RGB, not luminance
uniform int uAiEnabled;
uniform float uScanlineIntensity;
uniform float uMaskStrength;
uniform int uMaskType;      // 0 none, 1 aperture grille, 2 shadow mask, 3 slot
uniform int uShowGuide;
uniform sampler2D uYHiTex;   // ESPCN reconstructed luminance
uniform sampler2D uBaseTex;  // native-res RGB matching uYHiTex
uniform int uUseNeuralY;
uniform int uPixelEdge;    // Scale2x-style edge reconstruction

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Pixel-art sampling: keeps each native pixel a crisp square but antialiases
// its edge over exactly one output pixel. Plain bilinear turns sprite art into
// mush at 4x; plain nearest gives ragged diagonals.
vec2 sharpUV(vec2 uv, vec2 texSize) {
    vec2 p = uv * texSize;
    vec2 c = floor(p) + 0.5;
    vec2 w = max(fwidth(p), vec2(1e-5));
    vec2 t = clamp((p - c) / w, -0.5, 0.5);
    return (c + t) / texSize;
}

bool inside(vec2 p, vec4 r) {
    return p.x >= r.x && p.x < r.x + r.z && p.y >= r.y && p.y < r.y + r.w;
}

// uv is normalized inside the source window.
// Mapped to TEXEL CENTRES (+0.5 .. size-0.5): sampling the exact rect border
// makes the bilinear filter blend in whatever sits outside the source window
// (e.g. the alignment guide), which shows up as a coloured fringe around the
// whole enhanced image.
vec3 sampleSource(vec2 uv) {
    // uv spans the source rect edge to edge, so uv * size IS the pixel
    // position inside it: uv=(i+0.5)/size must land exactly on texel centre
    // i+0.5. The previous mapping (0.5 + uv*(size-1)) spread uv across texel
    // CENTRES instead, which drifts by up to a full texel across the image and
    // put every sample half way between two native pixels - every engine then
    // received a 50/50 blend of neighbours, i.e. a permanently blurred input.
    // Clamping to [0.5, size-0.5] keeps the bilinear tap inside the rect, so
    // nothing outside (like the alignment guide) can bleed in.
    vec2 pos = clamp(uv * uSourceRect.zw, vec2(0.5), uSourceRect.zw - vec2(0.5));
    return texture(uExternalTex, (uSourceRect.xy + pos) / uCaptureSize).rgb;
}

// Same sampling as above but snapped to native pixel cells.
vec3 sampleSourceSharp(vec2 uv) {
    return sampleSource(sharpUV(uv, uNativeRes));
}

/** Colour of one native texel, addressed by integer index. */
vec3 nativeTexel(vec2 idx) {
    vec2 clamped = clamp(idx, vec2(0.0), uNativeRes - vec2(1.0));
    return sampleSource((clamped + vec2(0.5)) / uNativeRes);
}

bool sameColor(vec3 a, vec3 b) {
    return dot(abs(a - b), vec3(1.0)) < 0.06;
}

/**
 * Scale2x / AdvMAME2x edge reconstruction, evaluated at an arbitrary output
 * scale instead of a fixed 2x.
 *
 * Why this and not the neural net: pixel art is not a downscaled photo, it is
 * finished artwork at its native resolution, so there is no lost detail for a
 * network to recover. What DOES improve it is deciding, from the neighbours,
 * whether a staircase of pixels was meant to be a diagonal - and rebuilding it
 * as one. That is a comparison, not a regression, which is why a handful of
 * equality tests beats a small CNN here.
 *
 * Each native pixel is split into four quadrants; a quadrant takes a
 * neighbour's colour when a diagonal edge runs through that corner. Flat areas
 * and single-pixel details are left exactly as they are.
 */
vec3 pixelEdgeUpscale(vec2 uv) {
    vec2 p = uv * uNativeRes;
    vec2 idx = floor(p);
    vec2 fp = fract(p);

    vec3 E = nativeTexel(idx);
    vec3 B = nativeTexel(idx + vec2( 0.0, -1.0));
    vec3 D = nativeTexel(idx + vec2(-1.0,  0.0));
    vec3 F = nativeTexel(idx + vec2( 1.0,  0.0));
    vec3 H = nativeTexel(idx + vec2( 0.0,  1.0));

    bool bd = sameColor(B, D);
    bool bf = sameColor(B, F);
    bool hd = sameColor(H, D);
    bool hf = sameColor(H, F);

    vec3 topLeft     = (bd && !bf && !hd) ? D : E;
    vec3 topRight    = (bf && !bd && !hf) ? F : E;
    vec3 bottomLeft  = (hd && !bd && !hf) ? D : E;
    vec3 bottomRight = (hf && !bf && !hd) ? F : E;

    // Antialias the quadrant boundary over one output pixel, so the rebuilt
    // diagonal is smooth instead of being a smaller staircase.
    vec2 w = clamp((fp - vec2(0.5)) / max(fwidth(p), vec2(1e-5)) + vec2(0.5),
                   vec2(0.0), vec2(1.0));
    return mix(mix(topLeft, topRight, w.x), mix(bottomLeft, bottomRight, w.x), w.y);
}

void main() {
    // gl_FragCoord is bottom-up; convert to Android top-left screen space.
    vec2 px = vec2(gl_FragCoord.x, uScreenSize.y - gl_FragCoord.y);

    // Alignment guide: a ring drawn just outside the source window so the
    // user can line up RetroArch's custom viewport with it.
    // A 2px gap is kept between the ring and the source window so the ring
    // itself can never leak into the sampled area.
    if (uShowGuide != 0) {
        vec4 outer = vec4(uSourceRect.x - 6.0, uSourceRect.y - 6.0,
                          uSourceRect.z + 12.0, uSourceRect.w + 12.0);
        vec4 gap = vec4(uSourceRect.x - 2.0, uSourceRect.y - 2.0,
                        uSourceRect.z + 4.0, uSourceRect.w + 4.0);
        if (inside(px, outer) && !inside(px, gap)) {
            fragColor = vec4(1.0, 0.0, 0.55, 1.0);
            return;
        }
    }

    // Never paint over the capture source - but ONLY when our output is inside
    // the capture. Under whole-screen capture, painting there would feed our
    // own output back in and the picture self-destructs within a few frames.
    // Under single-app capture we are not in the mirror at all, and punching
    // this hole is precisely what leaves the small native picture showing
    // through the middle of the enhanced one.
    if (uProtectSource != 0 && inside(px, uSourceRect)) discard;
    if (!inside(px, uOutputRect)) discard;

    vec2 uv = (px - uOutputRect.xy) / uOutputRect.zw;
    vec2 texelSize = 1.0 / max(uNativeRes, vec2(1.0));
    vec3 resultColor;

    if (uPixelEdge != 0) {
        resultColor = pixelEdgeUpscale(uv);
    } else if (uUseNeuralY != 0) {
        // NCNN ESPCN path: the network reconstructs the luminance channel at
        // native*scale; chroma rides along on a bilinear tap of the SAME frame
        // (the eye is far less sensitive to chroma resolution).
        vec3 base = texture(uBaseTex, sharpUV(uv, uNativeRes)).rgb;
        // The network output has its own pixel grid, native * uAiScale.
        // Sampling it with plain bilinear smears that grid across the output
        // and drags the whole picture back to mush - snap to it the same way
        // as the base. The factor MUST track the selected AI scale: this was
        // hard-coded to 3.0 while the UI offered 1x/2x/3x/4x, so every scale
        // except 3x snapped to a grid the texture did not have and threw away
        // exactly the detail the network had just reconstructed.
        vec3 hi = texture(uYHiTex, sharpUV(uv, uNativeRes * uAiScale)).rgb;
        if (uNeuralRgb != 0) {
            // RetroAI reconstructs colour too - take it whole. Re-deriving
            // chroma from the input here would undo the repainting that is the
            // entire point of the model.
            resultColor = clamp(hi, 0.0, 1.0);
        } else {
            // ESPCN rebuilds luminance only; chroma rides along from the same
            // frame's base texture, which is what keeps the two in step.
            resultColor = clamp(base + (hi.r - getLuma(base)), 0.0, 1.0);
        }
    } else if (uAiEnabled != 0) {
        // Edge-directed adaptive upscale over native game pixels.
        vec3 c  = sampleSource(uv);
        vec3 t  = sampleSource(uv + vec2(0.0, -texelSize.y));
        vec3 b  = sampleSource(uv + vec2(0.0,  texelSize.y));
        vec3 l  = sampleSource(uv + vec2(-texelSize.x, 0.0));
        vec3 r  = sampleSource(uv + vec2( texelSize.x, 0.0));

        vec3 tl = sampleSource(uv + vec2(-texelSize.x, -texelSize.y));
        vec3 tr = sampleSource(uv + vec2( texelSize.x, -texelSize.y));
        vec3 bl = sampleSource(uv + vec2(-texelSize.x,  texelSize.y));
        vec3 br = sampleSource(uv + vec2( texelSize.x,  texelSize.y));

        float lumaC  = getLuma(c);
        float lumaT  = getLuma(t);
        float lumaB  = getLuma(b);
        float lumaL  = getLuma(l);
        float lumaR  = getLuma(r);
        float lumaTL = getLuma(tl);
        float lumaTR = getLuma(tr);
        float lumaBL = getLuma(bl);
        float lumaBR = getLuma(br);

        float gx = (lumaTR + 2.0 * lumaR + lumaBR) - (lumaTL + 2.0 * lumaL + lumaBL);
        float gy = (lumaBL + 2.0 * lumaB + lumaBR) - (lumaTL + 2.0 * lumaT + lumaTR);
        float gradMagnitude = sqrt(gx * gx + gy * gy);

        if (gradMagnitude > 0.02) {
            vec2 dir = normalize(vec2(-gy, gx));
            vec3 s1 = sampleSource(uv + dir * texelSize * 0.40);
            vec3 s2 = sampleSource(uv - dir * texelSize * 0.40);
            vec3 edgeColor = (s1 + s2 + c * 2.0) * 0.25;

            float unsharp = lumaC + (lumaC - (lumaT + lumaB + lumaL + lumaR) * 0.25) * 0.75;
            resultColor = edgeColor * (unsharp / max(lumaC, 0.001));
        } else {
            resultColor = c;
        }
        resultColor = clamp(resultColor, 0.0, 1.0);
    } else {
        resultColor = sampleSourceSharp(uv);
    }

    // ---------------------------------------------------------------
    // CRT display simulation.
    //
    // Done in LINEAR light: sRGB values are gamma encoded, so multiplying them
    // by a mask (as the previous version did) darkens midtones far more than
    // physics says it should - that is what made the picture look muddy rather
    // than like a screen. Lottes' masks assume linear input for exactly this
    // reason, so decode, apply, re-encode.
    // ---------------------------------------------------------------
    if (uScanlineIntensity > 0.001 || uMaskStrength > 0.001) {
        vec3 linear = pow(max(resultColor, vec3(0.0)), vec3(2.2));

        // Scanlines belong to the SOURCE raster: one scanline per game line.
        // Gaussian beam profile rather than a sine - a phosphor line is a beam
        // with a width, and the flat-topped falloff is what keeps bright areas
        // bright instead of dimming everything uniformly.
        if (uScanlineIntensity > 0.001) {
            float linePos = fract(uv.y * uNativeRes.y) - 0.5;
            float beam = mix(0.55, 0.30, uScanlineIntensity);
            float weight = exp(-(linePos * linePos) / (2.0 * beam * beam));
            // Normalised so an all-white field keeps its brightness.
            float norm = beam * 2.5066282746;  // sigma * sqrt(2*pi)
            linear *= mix(1.0, weight / max(norm, 0.35), uScanlineIntensity);
        }

        // The mask belongs to the DISPLAY: indexed by screen pixel, fixed pitch
        // on the glass, independent of the console's resolution.
        if (uMaskStrength > 0.001) {
            float dark = 1.0 - uMaskStrength;
            float light = 1.0 + uMaskStrength * 0.6;
            vec3 mask = vec3(dark);
            vec2 mp = floor(px);

            if (uMaskType == 1) {
                // Aperture grille: vertical RGB stripes, 3 screen px pitch.
                float slot = fract(mp.x / 3.0);
                if (slot < 0.333) mask.r = light;
                else if (slot < 0.666) mask.g = light;
                else mask.b = light;
            } else if (uMaskType == 2) {
                // TV shadow mask: staggered triads, every other column shifted.
                float line = light;
                float odd = fract(mp.x / 6.0) < 0.5 ? 1.0 : 0.0;
                if (fract((mp.y + odd) / 2.0) < 0.5) line = dark;
                float slot = fract(mp.x / 3.0);
                if (slot < 0.333) mask.r = light;
                else if (slot < 0.666) mask.g = light;
                else mask.b = light;
                mask *= line;
            } else if (uMaskType == 3) {
                // Slot mask (VGA style): triads sheared along Y.
                vec2 sp = floor(mp * vec2(1.0, 0.5));
                float slot = fract((sp.x + sp.y * 3.0) / 6.0);
                if (slot < 0.333) mask.r = light;
                else if (slot < 0.666) mask.g = light;
                else mask.b = light;
            }
            linear *= mask;
        }

        resultColor = pow(max(linear, vec3(0.0)), vec3(1.0 / 2.2));
    }

    fragColor = vec4(resultColor, 1.0);
}
)glsl";

// Capture pass: copies the source window into a native-res RGB texture
// (240x160 for GBA). That copy is both what gets read back for the network and
// what the composite samples, which is what keeps luminance and chroma on the
// same frame. Row 0 of the render target is the TOP row of the source window,
// so the CPU buffer, the network output and the composite sampling all share
// one top-down convention.
static const char* Y_EXTRACT_FRAG_SRC = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

out vec4 fragColor;

uniform samplerExternalOES uExternalTex;
uniform vec2 uCaptureSize;
uniform vec4 uSourceRect;
uniform vec2 uNativeRes;

void main() {
    vec2 uv = gl_FragCoord.xy / uNativeRes;
    // Same texel-centre mapping as the composite pass - see sampleSource().
    vec2 pos = clamp(uv * uSourceRect.zw, vec2(0.5), uSourceRect.zw - vec2(0.5));
    fragColor = vec4(texture(uExternalTex, (uSourceRect.xy + pos) / uCaptureSize).rgb, 1.0);
}
)glsl";

GlRenderer::GlRenderer() = default;

GlRenderer::~GlRenderer() {
    release();
}

bool GlRenderer::init(ANativeWindow* window, int screenWidth, int screenHeight) {
    nativeWindow_ = window;
    screenWidth_ = screenWidth;
    screenHeight_ = screenHeight;

    if (!initEGL(window)) {
        return false;
    }

    initGLResources();

    // Paint one fully transparent frame immediately: the SurfaceView must
    // never show stale/undefined content while the pipeline warms up.
    drawTransparent();

    // Release context from initialization thread so worker thread can acquire it
    eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    ALOGI("GlRenderer initialized: %dx%d (EGL ready for worker thread)", screenWidth_, screenHeight_);
    return true;
}

bool GlRenderer::initEGL(ANativeWindow* window) {
    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        ALOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(eglDisplay_, &major, &minor)) {
        ALOGE("eglInitialize failed");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        ALOGE("eglChooseConfig failed");
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    eglContext_ = eglCreateContext(eglDisplay_, config, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        ALOGE("eglCreateContext failed: 0x%x", eglGetError());
        return false;
    }

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, config, window, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        ALOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        ALOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    return true;
}

GLuint GlRenderer::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char infoLog[1024];
        glGetShaderInfoLog(shader, sizeof(infoLog), nullptr, infoLog);
        ALOGE("Shader compile error (%d): %s", type, infoLog);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint GlRenderer::createProgram(const char* vertSource, const char* fragSource) {
    GLuint vert = compileShader(GL_VERTEX_SHADER, vertSource);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fragSource);
    if (!vert || !frag) return 0;

    GLuint program = glCreateProgram();
    glAttachShader(program, vert);
    glAttachShader(program, frag);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char infoLog[1024];
        glGetProgramInfoLog(program, sizeof(infoLog), nullptr, infoLog);
        ALOGE("Program link error: %s", infoLog);
        glDeleteProgram(program);
        return 0;
    }

    glDeleteShader(vert);
    glDeleteShader(frag);
    return program;
}

void GlRenderer::initGLResources() {
    const float quadVertices[] = {
        -1.0f,  1.0f,
        -1.0f, -1.0f,
         1.0f,  1.0f,
         1.0f, -1.0f
    };

    glGenVertexArrays(1, &quadVao_);
    glBindVertexArray(quadVao_);

    glGenBuffers(1, &quadVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), (void*)0);

    glBindVertexArray(0);

    oesPassProgram_ = createProgram(VERTEX_SHADER_SRC, AI_SCALER_FRAG_SRC);
    if (oesPassProgram_ == 0) {
        ALOGE("Failed to create AI Scaler Program! Overlay will stay transparent.");
    } else {
        uni_.screenSize  = glGetUniformLocation(oesPassProgram_, "uScreenSize");
        uni_.captureSize = glGetUniformLocation(oesPassProgram_, "uCaptureSize");
        uni_.sourceRect  = glGetUniformLocation(oesPassProgram_, "uSourceRect");
        uni_.outputRect  = glGetUniformLocation(oesPassProgram_, "uOutputRect");
        uni_.nativeRes   = glGetUniformLocation(oesPassProgram_, "uNativeRes");
        uni_.aiScale     = glGetUniformLocation(oesPassProgram_, "uAiScale");
        uni_.protectSource = glGetUniformLocation(oesPassProgram_, "uProtectSource");
        uni_.neuralRgb   = glGetUniformLocation(oesPassProgram_, "uNeuralRgb");
        uni_.aiEnabled   = glGetUniformLocation(oesPassProgram_, "uAiEnabled");
        uni_.scanline    = glGetUniformLocation(oesPassProgram_, "uScanlineIntensity");
        uni_.lcdGrid     = glGetUniformLocation(oesPassProgram_, "uMaskStrength");
        uni_.maskType    = glGetUniformLocation(oesPassProgram_, "uMaskType");
        uni_.showGuide   = glGetUniformLocation(oesPassProgram_, "uShowGuide");
        uni_.externalTex = glGetUniformLocation(oesPassProgram_, "uExternalTex");
        uni_.yHiTex      = glGetUniformLocation(oesPassProgram_, "uYHiTex");
        uni_.baseTex     = glGetUniformLocation(oesPassProgram_, "uBaseTex");
        uni_.useNeuralY  = glGetUniformLocation(oesPassProgram_, "uUseNeuralY");
        uni_.pixelEdge   = glGetUniformLocation(oesPassProgram_, "uPixelEdge");
    }

    yExtractProgram_ = createProgram(VERTEX_SHADER_SRC, Y_EXTRACT_FRAG_SRC);
    if (yExtractProgram_ == 0) {
        ALOGE("Failed to create Y extraction program - ESPCN path disabled.");
    } else {
        yUni_.externalTex = glGetUniformLocation(yExtractProgram_, "uExternalTex");
        yUni_.captureSize = glGetUniformLocation(yExtractProgram_, "uCaptureSize");
        yUni_.sourceRect  = glGetUniformLocation(yExtractProgram_, "uSourceRect");
        yUni_.nativeRes   = glGetUniformLocation(yExtractProgram_, "uNativeRes");
    }

    glGenFramebuffers(1, &aiFbo_);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);

    lastFpsCalcTime_ = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
}

void GlRenderer::setGeometry(const RectI& source, const RectI& output, bool showSourceGuide,
                             bool protectSource) {
    sourceRect_ = source;
    outputRect_ = output;
    showSourceGuide_ = showSourceGuide;
    protectSource_ = protectSource;
    hasGeometry_ = source.valid() && output.valid();
    ALOGI("Geometry: source=(%d,%d %dx%d) output=(%d,%d %dx%d) guide=%d protect=%d valid=%d",
          source.x, source.y, source.w, source.h,
          output.x, output.y, output.w, output.h,
          showSourceGuide ? 1 : 0, protectSource ? 1 : 0, hasGeometry_ ? 1 : 0);
}

void GlRenderer::setMaskType(int type) {
    maskType_ = type;
    ALOGI("CRT mask type = %d", type);
}

void GlRenderer::setPixelEdgeEnabled(bool enabled) {
    pixelEdgeEnabled_ = enabled;
    ALOGI("Pixel-edge reconstruction %s", enabled ? "on" : "off");
}

void GlRenderer::setRenderConfig(
    bool isAiEnabled,
    int consoleNativeWidth,
    int consoleNativeHeight,
    float scanlineIntensity,
    float lcdGridIntensity
) {
    isAiEnabled_ = isAiEnabled;
    consoleNativeWidth_ = consoleNativeWidth > 0 ? consoleNativeWidth : 240;
    consoleNativeHeight_ = consoleNativeHeight > 0 ? consoleNativeHeight : 160;
    scanlineIntensity_ = scanlineIntensity;
    lcdGridIntensity_ = lcdGridIntensity;
    ALOGI("Render config: AI=%d, Native=%dx%d, Scanline=%.2f, LCD=%.2f",
          isAiEnabled, consoleNativeWidth_, consoleNativeHeight_,
          scanlineIntensity_, lcdGridIntensity_);
}

bool GlRenderer::loadEspcnModel(const char* paramText,
                                const unsigned char* binData,
                                size_t binSize,
                                int scaleFactor,
                                bool preferGpu,
                                int channels) {
    // The worker owns the net while it runs; stop it before swapping models.
    stopAiWorker();
    bool ok = espcnEngine_.loadModel(paramText, binData, binSize, scaleFactor,
                                     preferGpu, channels);
    // Force reallocation so the hi-res texture matches the new scale.
    aiTexWidth_ = 0;
    aiTexHeight_ = 0;
    espcnFailureStreak_ = 0;
    hasAiPair_ = false;
    return ok;
}

void GlRenderer::startAiWorker() {
    if (aiThread_.joinable()) return;
    {
        std::lock_guard<std::mutex> lock(aiMutex_);
        aiShutdown_ = false;
        aiJobPending_ = false;
        aiResultReady_ = false;
        aiBusy_ = false;
        aiFailed_ = false;
    }
    aiThread_ = std::thread(&GlRenderer::aiWorkerLoop, this);
}

void GlRenderer::stopAiWorker() {
    if (!aiThread_.joinable()) return;
    {
        std::lock_guard<std::mutex> lock(aiMutex_);
        aiShutdown_ = true;
    }
    aiCv_.notify_all();
    aiThread_.join();
    std::lock_guard<std::mutex> lock(aiMutex_);
    aiJobPending_ = false;
    aiResultReady_ = false;
    aiBusy_ = false;
}

void GlRenderer::aiWorkerLoop() {
    // Inference belongs on the big cores; the render thread is bound already.
    CpuAffinity::bindToBigCores();

    std::vector<uint8_t> localIn;
    std::vector<uint8_t> localOut;

    std::unique_lock<std::mutex> lock(aiMutex_);
    while (true) {
        aiCv_.wait(lock, [this] { return aiShutdown_ || aiJobPending_; });
        if (aiShutdown_) break;

        localIn.swap(aiInput_);
        const int w = aiJobWidth_;
        const int h = aiJobHeight_;
        const int scale = aiScale_;
        aiJobPending_ = false;
        aiBusy_ = true;
        lock.unlock();

        const int ch = espcnEngine_.channels();
        localOut.resize((size_t)w * scale * h * scale * ch);
        float ms = 0.0f;
        bool ok = espcnEngine_.process(
            localIn.data(), w, h, localOut.data(), w * scale, h * scale, ms);

        lock.lock();
        aiBusy_ = false;
        if (ok) {
            aiOutput_.swap(localOut);
            aiResultReady_ = true;
            aiLastMs_ = ms;
        } else {
            aiFailed_ = true;
        }
    }
}

bool GlRenderer::ensureAiTextures() {
    const int w = consoleNativeWidth_;
    const int h = consoleNativeHeight_;
    const int scale = espcnEngine_.getScaleFactor();
    if (w <= 0 || h <= 0 || scale <= 0) return false;

    if (baseTex_[0] != 0 && aiTexWidth_ == w && aiTexHeight_ == h && aiScale_ == scale) {
        return true;
    }

    aiTexWidth_ = w;
    aiTexHeight_ = h;
    aiScale_ = scale;
    hasAiPair_ = false;

    if (baseTex_[0] != 0) glDeleteTextures(2, baseTex_);
    if (yHiTex_ != 0) glDeleteTextures(1, &yHiTex_);

    // RGBA8 render targets: readable with GL_RGBA/GL_UNSIGNED_BYTE on every ES3
    // implementation, unlike R8 whose readback format is driver dependent.
    glGenTextures(2, baseTex_);
    for (GLuint tex : baseTex_) {
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    glGenTextures(1, &yHiTex_);
    glBindTexture(GL_TEXTURE_2D, yHiTex_);
    // RGB for RetroAI, single-channel for the ESPCN models. RetroAI rebuilds
    // colour as well as detail, and forcing it back through a luminance-only
    // texture would throw away the half of its output that makes it look
    // repainted rather than sharpened.
    const bool rgb = espcnEngine_.channels() == 3;
    glTexImage2D(GL_TEXTURE_2D, 0, rgb ? GL_RGB8 : GL_R8, w * scale, h * scale, 0,
                 rgb ? GL_RGB : GL_RED, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    glBindFramebuffer(GL_FRAMEBUFFER, aiFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, baseTex_[0], 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        ALOGE("AI capture FBO incomplete: 0x%x", status);
        aiTexWidth_ = 0;
        return false;
    }

    readbackBuffer_.assign((size_t)w * h * 4, 0);
    ALOGI("AI textures ready: %dx%d -> %dx%d (scale %d)", w, h, w * scale, h * scale, scale);
    return true;
}

bool GlRenderer::runEspcnPass(GLuint externalTexId, int frameWidth, int frameHeight) {
    if (!espcnEngine_.isReady() || yExtractProgram_ == 0 || espcnFailureStreak_ > 8) {
        return false;
    }
    if (!ensureAiTextures()) return false;

    startAiWorker();

    const int w = aiTexWidth_;
    const int h = aiTexHeight_;
    const int scale = aiScale_;
    const int aiChannels = espcnEngine_.channels();

    // 1. Collect a finished result, if any. Only now do the base texture and
    //    the reconstructed luminance describe the same frame.
    bool uploadReady = false;
    {
        std::lock_guard<std::mutex> lock(aiMutex_);
        if (aiFailed_) {
            aiFailed_ = false;
            espcnFailureStreak_++;
            if (espcnFailureStreak_ > 8) {
                ALOGE("ESPCN failed %d times in a row - falling back to the shader path.",
                      espcnFailureStreak_);
                return false;
            }
        }
        const size_t expected = (size_t)w * scale * h * scale * aiChannels;
        if (aiResultReady_ && aiOutput_.size() == expected) {
            aiResultReady_ = false;
            uploadReady = true;
            stats_.aiMs = aiLastMs_;
            glBindTexture(GL_TEXTURE_2D, yHiTex_);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w * scale, h * scale,
                            aiChannels == 3 ? GL_RGB : GL_RED,
                            GL_UNSIGNED_BYTE, aiOutput_.data());
            glBindTexture(GL_TEXTURE_2D, 0);
        } else if (aiResultReady_) {
            // Stale result from a different geometry - drop it.
            aiResultReady_ = false;
        }
    }
    if (uploadReady) {
        aiDisplayIndex_ = aiSubmitIndex_;
        espcnFailureStreak_ = 0;
        hasAiPair_ = true;
    }

    // 2. Submit a new frame only when the worker is idle, into the buffer that
    //    is NOT currently being displayed.
    bool workerIdle;
    {
        std::lock_guard<std::mutex> lock(aiMutex_);
        workerIdle = !aiJobPending_ && !aiBusy_ && !aiResultReady_;
    }

    if (workerIdle) {
        auto captureStart = std::chrono::high_resolution_clock::now();
        const int target = 1 - aiDisplayIndex_;

        glBindFramebuffer(GL_FRAMEBUFFER, aiFbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, baseTex_[target], 0);
        glViewport(0, 0, w, h);
        glUseProgram(yExtractProgram_);
        glUniform2f(yUni_.captureSize, (float)frameWidth, (float)frameHeight);
        glUniform4f(yUni_.sourceRect, (float)sourceRect_.x, (float)sourceRect_.y,
                    (float)sourceRect_.w, (float)sourceRect_.h);
        glUniform2f(yUni_.nativeRes, (float)w, (float)h);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
        glUniform1i(yUni_.externalTex, 0);

        glBindVertexArray(quadVao_);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);

        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, readbackBuffer_.data());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        {
            std::lock_guard<std::mutex> lock(aiMutex_);
            const size_t pixels = (size_t)w * h;
            aiInput_.resize(pixels * aiChannels);
            if (aiChannels == 3) {
                // Drop alpha, keep RGB interleaved as ncnn's PIXEL_RGB wants.
                for (size_t i = 0; i < pixels; ++i) {
                    const uint8_t* px = &readbackBuffer_[i * 4];
                    aiInput_[i * 3 + 0] = px[0];
                    aiInput_[i * 3 + 1] = px[1];
                    aiInput_[i * 3 + 2] = px[2];
                }
            } else {
                for (size_t i = 0; i < pixels; ++i) {
                    const uint8_t* px = &readbackBuffer_[i * 4];
                    aiInput_[i] = (uint8_t)((px[0] * 77 + px[1] * 150 + px[2] * 29) >> 8);
                }
            }
            aiJobWidth_ = w;
            aiJobHeight_ = h;
            aiJobPending_ = true;
        }
        aiCv_.notify_one();
        aiSubmitIndex_ = target;

        auto captureEnd = std::chrono::high_resolution_clock::now();
        stats_.captureMs = std::chrono::duration<float, std::milli>(captureEnd - captureStart).count();
    }

    return hasAiPair_;
}

// Downscaled frame used to locate the emulator's picture. Small on purpose:
// 320x240 is plenty to find a rectangle and costs ~300KB to read back.
static const int DETECT_W = 320;
static const int DETECT_H = 240;

void GlRenderer::requestSourceDetection(int expectedWidth, int expectedHeight) {
    detectExpectedW_ = expectedWidth > 0 ? expectedWidth : 240;
    detectExpectedH_ = expectedHeight > 0 ? expectedHeight : 160;
    detectState_ = DetectState::Blanking;
    detectBlankFrames_ = 0;
    detectedValid_ = false;
    ALOGI("Source detection requested.");
}

bool GlRenderer::getDetectedRect(RectI& out) const {
    if (!detectedValid_) return false;
    out = detectedRect_;
    return true;
}

/**
 * Renders the whole captured frame into a small target, reads it back and takes
 * the bounding box of everything that is not black. RetroArch clears the area
 * around its viewport to black, so that box IS the emulator's picture.
 */
bool GlRenderer::readReducedFrame(GLuint externalTexId, int frameWidth, int frameHeight) {
    if (yExtractProgram_ == 0 || aiFbo_ == 0) return false;

    if (detectTex_ == 0) {
        glGenTextures(1, &detectTex_);
        glBindTexture(GL_TEXTURE_2D, detectTex_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, DETECT_W, DETECT_H, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        detectBuffer_.assign((size_t)DETECT_W * DETECT_H * 4, 0);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, aiFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, detectTex_, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ALOGE("detection FBO incomplete");
        return false;
    }

    glViewport(0, 0, DETECT_W, DETECT_H);
    glUseProgram(yExtractProgram_);
    glUniform2f(yUni_.captureSize, (float)frameWidth, (float)frameHeight);
    // Sample the WHOLE captured frame, not the configured source window.
    glUniform4f(yUni_.sourceRect, 0.0f, 0.0f, (float)frameWidth, (float)frameHeight);
    glUniform2f(yUni_.nativeRes, (float)DETECT_W, (float)DETECT_H);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
    glUniform1i(yUni_.externalTex, 0);

    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, DETECT_W, DETECT_H, GL_RGBA, GL_UNSIGNED_BYTE, detectBuffer_.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return true;
}

/**
 * Marker geometry, shared by the painter and the sampler so they cannot drift.
 * Top-left corner, an eighth of the screen each way - big enough to survive the
 * reduction to 320x240, small enough that the flash is barely noticeable.
 */
static const int PROBE_DIV = 8;
/** Opaque magenta: nothing a game draws is likely to sit flat on this. */
static const float PROBE_R = 1.0f, PROBE_G = 0.0f, PROBE_B = 1.0f;

void GlRenderer::drawProbeMarker(bool on) {
    glViewport(0, 0, screenWidth_, screenHeight_);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (on) {
        const int w = screenWidth_ / PROBE_DIV;
        const int h = screenHeight_ / PROBE_DIV;
        // Scissor is bottom-left based; the marker is defined top-left.
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, screenHeight_ - h, w, h);
        glClearColor(PROBE_R, PROBE_G, PROBE_B, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
    }
    eglSwapBuffers(eglDisplay_, eglSurface_);
}

void GlRenderer::sampleProbeRegion(float outRgb[3]) const {
    // detectBuffer_ rows run top-down in screen terms - the same convention
    // runDetectionPass relies on when it scales bestMinY back up.
    const int w = DETECT_W / PROBE_DIV;
    const int h = DETECT_H / PROBE_DIV;
    double sum[3] = {0.0, 0.0, 0.0};
    int count = 0;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t* px = &detectBuffer_[((size_t)y * DETECT_W + x) * 4];
            sum[0] += px[0];
            sum[1] += px[1];
            sum[2] += px[2];
            ++count;
        }
    }
    const double n = count > 0 ? (double)count : 1.0;
    outRgb[0] = (float)(sum[0] / n / 255.0);
    outRgb[1] = (float)(sum[1] / n / 255.0);
    outRgb[2] = (float)(sum[2] / n / 255.0);
}

/**
 * Renders the capture window into a native-resolution target and reads it back.
 *
 * Reuses Y_EXTRACT_FRAG_SRC, which despite the name emits RGB through exactly
 * the same texel-centre mapping the composite pass uses. Because the source
 * rect is snapped to an integer multiple of the native size, sampling those
 * centres lands in the middle of each block and recovers the emulator's
 * original pixels unchanged - this is a lossless grab, not a downscale.
 */
void GlRenderer::runNativeCapture(GLuint externalTexId, int frameWidth, int frameHeight) {
    nativeCaptureRequested_ = false;
    if (yExtractProgram_ == 0 || aiFbo_ == 0) return;
    const int w = consoleNativeWidth_;
    const int h = consoleNativeHeight_;
    if (w <= 0 || h <= 0 || !sourceRect_.valid()) {
        ALOGW("native capture skipped: geometry not ready");
        return;
    }

    if (captureTex_ == 0) {
        glGenTextures(1, &captureTex_);
        glBindTexture(GL_TEXTURE_2D, captureTex_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        capturedW_ = 0;
    }
    if (capturedW_ != w || capturedH_ != h) {
        glBindTexture(GL_TEXTURE_2D, captureTex_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, aiFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, captureTex_, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ALOGE("native capture FBO incomplete");
        return;
    }

    glViewport(0, 0, w, h);
    glUseProgram(yExtractProgram_);
    glUniform2f(yUni_.captureSize, (float)frameWidth, (float)frameHeight);
    glUniform4f(yUni_.sourceRect, (float)sourceRect_.x, (float)sourceRect_.y,
                (float)sourceRect_.w, (float)sourceRect_.h);
    glUniform2f(yUni_.nativeRes, (float)w, (float)h);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
    glUniform1i(yUni_.externalTex, 0);
    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    capturedFrame_.assign((size_t)w * h * 4, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, capturedFrame_.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    capturedW_ = w;
    capturedH_ = h;
    capturedValid_ = true;
    ALOGI("native frame captured: %dx%d from source (%d,%d %dx%d)",
          w, h, sourceRect_.x, sourceRect_.y, sourceRect_.w, sourceRect_.h);
}

bool GlRenderer::takeCapturedFrame(std::vector<uint8_t>& out, int& w, int& h) {
    if (!capturedValid_) return false;
    out = capturedFrame_;
    w = capturedW_;
    h = capturedH_;
    capturedValid_ = false;
    return true;
}

void GlRenderer::requestCaptureModeProbe() {
    probeState_ = ProbeState::MarkerOn;
    probeFrames_ = 0;
    captureMode_ = -1;
    ALOGI("Capture-mode probe requested.");
}

void GlRenderer::runCaptureModeProbe(GLuint externalTexId, int frameWidth, int frameHeight) {
    // The compositor needs a few frames to carry our marker through the mirror
    // before the readback can possibly show it.
    const int SETTLE_FRAMES = 4;

    if (probeState_ == ProbeState::MarkerOn) {
        drawProbeMarker(true);
        if (++probeFrames_ < SETTLE_FRAMES) return;
        if (readReducedFrame(externalTexId, frameWidth, frameHeight)) {
            sampleProbeRegion(probeSampleOn_);
        }
        probeFrames_ = 0;
        probeState_ = ProbeState::MarkerOff;
        return;
    }

    drawProbeMarker(false);
    if (++probeFrames_ < SETTLE_FRAMES) return;
    if (readReducedFrame(externalTexId, frameWidth, frameHeight)) {
        sampleProbeRegion(probeSampleOff_);
    }

    const float dR = probeSampleOn_[0] - probeSampleOff_[0];
    const float dG = probeSampleOn_[1] - probeSampleOff_[1];
    const float dB = probeSampleOn_[2] - probeSampleOff_[2];
    const float changed = sqrtf(dR * dR + dG * dG + dB * dB);

    const float mR = probeSampleOn_[0] - PROBE_R;
    const float mG = probeSampleOn_[1] - PROBE_G;
    const float mB = probeSampleOn_[2] - PROBE_B;
    const float looksLikeMarker = sqrtf(mR * mR + mG * mG + mB * mB);

    // Both tests have to agree. "Changed" alone would be fooled by a game
    // animating under the sampled corner; "looks like the marker" alone would
    // be fooled by a genuinely magenta frame.
    const bool captured = changed > 0.25f && looksLikeMarker < 0.35f;
    captureMode_ = captured ? 1 : 0;

    ALOGI("Capture-mode probe: %s (on=%.2f,%.2f,%.2f off=%.2f,%.2f,%.2f "
          "delta=%.2f markerDist=%.2f)",
          captured ? "WHOLE SCREEN (we are in the capture)"
                   : "SINGLE APP (our overlay is not captured)",
          probeSampleOn_[0], probeSampleOn_[1], probeSampleOn_[2],
          probeSampleOff_[0], probeSampleOff_[1], probeSampleOff_[2],
          changed, looksLikeMarker);

    probeState_ = ProbeState::Done;
}

void GlRenderer::runDetectionPass(GLuint externalTexId, int frameWidth, int frameHeight) {
    if (!readReducedFrame(externalTexId, frameWidth, frameHeight)) return;

    // A plain bounding box of everything non-black is not good enough: handheld
    // launchers park a system HUD strip (FPS/CPU/temps) at the top of the
    // screen, and it would stretch the box across the whole display. Label
    // connected regions instead and pick the one whose size best matches the
    // window we asked the emulator to draw.
    const int THRESHOLD = 18;
    const size_t pixelCount = (size_t)DETECT_W * DETECT_H;
    std::vector<uint8_t> mask(pixelCount, 0);
    for (size_t i = 0; i < pixelCount; ++i) {
        const uint8_t* px = &detectBuffer_[i * 4];
        int luma = (px[0] * 77 + px[1] * 150 + px[2] * 29) >> 8;
        mask[i] = (luma > THRESHOLD) ? 1 : 0;
    }

    const float sx = (float)frameWidth / (float)DETECT_W;
    const float sy = (float)frameHeight / (float)DETECT_H;
    // detectExpectedW_/H_ is the console's NATIVE size. The emulator may be
    // drawing at any integer multiple of it, so every plausible multiple is
    // tried and the closest one wins.
    const float nativeW = (float)detectExpectedW_ / sx;
    const float nativeH = (float)detectExpectedH_ / sy;

    // Anything whose size is not close to native*k for some k is not the game:
    // our own floating ball, the launcher's FPS strip, stray HUD bits.
    const float MAX_MISMATCH = 0.35f;

    // Size alone is too weak a test. The summed width+height error lets shapes
    // through whose PROPORTIONS are nothing like the console's, and those are
    // exactly the false positives that hurt: RetroArch sitting at its menu
    // fills the screen (1920x1080 reads as "8x of 240x160, 16% off"), and after
    // a rotation it re-lays out to something squashed (243x136 reads as "1x,
    // 16% off"). Both were accepted and became the sampled rect.
    //
    // Aspect ratio separates them cleanly because it is scale-free: a genuine
    // capture window is within ~1% of the console's ratio no matter which
    // integer multiple it is drawn at, while both false positives above are
    // ~19% out.
    const float MAX_ASPECT_ERROR = 0.08f;
    const float nativeAspect = nativeW / std::max(nativeH, 1e-5f);

    std::vector<int> stack;
    stack.reserve(pixelCount / 4);
    int bestArea = 0;
    float bestMismatch = 0.0f;
    int bestK = 1;
    bool found = false;
    int bestMinX = 0, bestMinY = 0, bestMaxX = 0, bestMaxY = 0;

    for (int start = 0; start < (int)pixelCount; ++start) {
        if (mask[start] != 1) continue;

        int minX = DETECT_W, minY = DETECT_H, maxX = -1, maxY = -1, area = 0;
        stack.clear();
        stack.push_back(start);
        mask[start] = 2;

        while (!stack.empty()) {
            int idx = stack.back();
            stack.pop_back();
            int x = idx % DETECT_W;
            int y = idx / DETECT_W;
            area++;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;

            // 8-connectivity: dithered game art can leave single-pixel gaps.
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= DETECT_W || ny >= DETECT_H) continue;
                    int nIdx = ny * DETECT_W + nx;
                    if (mask[nIdx] == 1) {
                        mask[nIdx] = 2;
                        stack.push_back(nIdx);
                    }
                }
            }
        }

        if (area < 16) continue;  // noise

        const float bw = (float)(maxX - minX + 1);
        const float bh = (float)(maxY - minY + 1);

        // Proportions first - it is the test that actually rejects things.
        const float aspectError =
            fabsf(bw / std::max(bh, 1e-5f) - nativeAspect) / std::max(nativeAspect, 1e-5f);
        if (aspectError > MAX_ASPECT_ERROR) continue;

        float mismatch = 1e9f;
        int matchedK = 1;
        for (int k = 1; k <= 8; ++k) {
            const float ew = nativeW * (float)k;
            const float eh = nativeH * (float)k;
            const float m = fabsf(bw - ew) / std::max(ew, 1.0f) +
                            fabsf(bh - eh) / std::max(eh, 1.0f);
            if (m < mismatch) {
                mismatch = m;
                matchedK = k;
            }
        }
        if (mismatch > MAX_MISMATCH) continue;

        // Several candidates can match; the game is the biggest of them.
        if (area > bestArea) {
            bestArea = area;
            bestMismatch = mismatch;
            bestK = matchedK;
            bestMinX = minX; bestMinY = minY; bestMaxX = maxX; bestMaxY = maxY;
            found = true;
        }
    }

    if (!found) {
        ALOGW("Source detection: no region matches %dx%d at any integer scale.",
              detectExpectedW_, detectExpectedH_);
        detectedValid_ = false;
        return;
    }

    // The measured box is SNAPPED to exactly native*k rather than used as-is,
    // and this is the whole point of the pass.
    //
    // Detection runs on a 320x240 reduction, so one detect row is 4.5 screen
    // pixels at 1080p: a boundary that lands half a row out yields something
    // like 240x162 for a 240x160 window. Two rows sounds harmless and is not.
    // The composite shader snaps uv to the NATIVE grid and then multiplies by
    // the SOURCE rect's size, so a rect that is 162 tall while native is 160
    // makes every tap land between two rows, drifting by a full native pixel
    // at mid-height and two by the bottom - every engine then samples a blend
    // of neighbours and the picture is uniformly soft. That is the same
    // "blurred input" failure the sampling convention was fixed for.
    //
    // Position is deliberately NOT snapped: being a pixel off merely shifts
    // the picture, which is invisible, whereas a non-integer SIZE resamples
    // every single pixel. Centre is preserved so the error splits evenly.
    const int snapW = detectExpectedW_ * bestK;
    const int snapH = detectExpectedH_ * bestK;
    const int measuredCx = (int)((bestMinX + bestMaxX + 1) * 0.5f * sx);
    const int measuredCy = (int)((bestMinY + bestMaxY + 1) * 0.5f * sy);

    detectedRect_.w = snapW;
    detectedRect_.h = snapH;
    detectedRect_.x = std::max(0, std::min(measuredCx - snapW / 2, frameWidth - snapW));
    detectedRect_.y = std::max(0, std::min(measuredCy - snapH / 2, frameHeight - snapH));
    detectedValid_ = true;
    ALOGI("Source detected: (%d,%d %dx%d) native=%dx%d k=%d mismatch=%.2f (raw %dx%d)",
          detectedRect_.x, detectedRect_.y, detectedRect_.w, detectedRect_.h,
          detectExpectedW_, detectExpectedH_, bestK, bestMismatch,
          (int)((bestMaxX - bestMinX + 1) * sx), (int)((bestMaxY - bestMinY + 1) * sy));
}

void GlRenderer::setPaused(bool paused) {
    if (paused_ == paused) return;
    paused_ = paused;
    pausedFrameDrawn_ = false;
    ALOGI("Renderer %s", paused ? "paused (target app not in foreground)" : "resumed");
}

bool GlRenderer::ensureEglContextCurrent() {
    if (eglDisplay_ == EGL_NO_DISPLAY || eglContext_ == EGL_NO_CONTEXT || eglSurface_ == EGL_NO_SURFACE) {
        return false;
    }
    if (eglGetCurrentContext() != eglContext_) {
        if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
            ALOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            return false;
        }
    }
    return true;
}

void GlRenderer::drawTransparent() {
    glViewport(0, 0, screenWidth_, screenHeight_);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay_, eglSurface_);
}

bool GlRenderer::clearOverlay() {
    if (!ensureEglContextCurrent()) {
        // Never fail quietly here. This is the safety valve the whole design
        // leans on - "any bad state degrades to fully transparent" - and when
        // it no-ops the overlay keeps covering the screen with a stale frame.
        // The usual cause is the capture thread still owning the EGL context,
        // i.e. it was called before the capture was paused and handed back.
        static int clearFailCount = 0;
        if (++clearFailCount % 30 == 1) {
            ALOGE("clearOverlay: no EGL context (is capture still running?) - "
                  "overlay NOT wiped, count=%d", clearFailCount);
        }
        return false;
    }
    drawTransparent();

    // An EGL context can only be current on one thread at a time. This is
    // usually called from the main thread (watchdog, pause, teardown) while the
    // capture thread owns the rendering, so it MUST be handed back - otherwise
    // every later frame fails with EGL_BAD_ACCESS, no frame ever lands, and the
    // stall watchdog concludes the pipeline is dead and kills the service.
    eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return true;
}

bool GlRenderer::renderFrame(GLuint externalTexId, int frameWidth, int frameHeight) {
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        return false;
    }
    if (!ensureEglContextCurrent()) {
        return false;
    }

    // Runs before everything else, including the pause gate: the answer decides
    // which geometry the whole session uses, and it needs frames to flow.
    if (probeState_ == ProbeState::MarkerOn || probeState_ == ProbeState::MarkerOff) {
        runCaptureModeProbe(externalTexId, frameWidth, frameHeight);
        return true;
    }

    // Detection must measure the emulator's output, not ours. Blank first, let
    // the compositor push a few of our transparent frames through the capture,
    // then measure.
    if (detectState_ == DetectState::Blanking) {
        drawTransparent();
        if (++detectBlankFrames_ >= 4) {
            detectState_ = DetectState::Measuring;
        }
        return true;
    }
    if (detectState_ == DetectState::Measuring) {
        runDetectionPass(externalTexId, frameWidth, frameHeight);
        detectState_ = DetectState::Idle;
        drawTransparent();
        return true;
    }

    // Target app is not on screen: wipe once, then do nothing at all. Repainting
    // a transparent frame 60 times a second would burn power for no pixels.
    if (paused_) {
        if (!pausedFrameDrawn_) {
            drawTransparent();
            pausedFrameDrawn_ = true;
        }
        return true;
    }

    // Anything not fully configured -> transparent overlay, never a black sheet.
    if (externalTexId == 0 || oesPassProgram_ == 0 || !hasGeometry_ ||
        frameWidth <= 0 || frameHeight <= 0) {
        drawTransparent();
        return false;
    }

    // Before the composite: the FBO and viewport it needs are reset by the
    // composite pass that follows, and the picture still gets drawn this frame.
    if (nativeCaptureRequested_) {
        runNativeCapture(externalTexId, frameWidth, frameHeight);
    }

    auto renderStartTime = std::chrono::high_resolution_clock::now();

    // NCNN ESPCN luminance reconstruction; falls back to the GPU shader
    // upscaler whenever the model is missing or inference fails.
    bool useNeuralY = false;
    if (isAiEnabled_ && !pixelEdgeEnabled_) {
        useNeuralY = runEspcnPass(externalTexId, frameWidth, frameHeight);
    }
    if (!useNeuralY) {
        stats_.aiMs = 0.0f;
    }

    glViewport(0, 0, screenWidth_, screenHeight_);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(oesPassProgram_);

    glUniform2f(uni_.screenSize, (float)screenWidth_, (float)screenHeight_);
    glUniform2f(uni_.captureSize, (float)frameWidth, (float)frameHeight);
    glUniform4f(uni_.sourceRect, (float)sourceRect_.x, (float)sourceRect_.y,
                (float)sourceRect_.w, (float)sourceRect_.h);
    glUniform4f(uni_.outputRect, (float)outputRect_.x, (float)outputRect_.y,
                (float)outputRect_.w, (float)outputRect_.h);
    glUniform2f(uni_.nativeRes, (float)consoleNativeWidth_, (float)consoleNativeHeight_);
    // Whatever yHiTex_ was actually allocated at - see ensureAiTextures().
    glUniform1f(uni_.aiScale, (float)(aiScale_ > 0 ? aiScale_ : 1));
    glUniform1i(uni_.protectSource, protectSource_ ? 1 : 0);
    glUniform1i(uni_.neuralRgb, espcnEngine_.channels() == 3 ? 1 : 0);
    glUniform1i(uni_.aiEnabled, isAiEnabled_ ? 1 : 0);
    glUniform1f(uni_.scanline, scanlineIntensity_);
    glUniform1f(uni_.lcdGrid, lcdGridIntensity_);
    glUniform1i(uni_.maskType, maskType_);
    glUniform1i(uni_.showGuide, showSourceGuide_ ? 1 : 0);

    glUniform1i(uni_.useNeuralY, useNeuralY ? 1 : 0);
    glUniform1i(uni_.pixelEdge, pixelEdgeEnabled_ ? 1 : 0);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTexId);
    glUniform1i(uni_.externalTex, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, yHiTex_);
    glUniform1i(uni_.yHiTex, 1);

    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, baseTex_[aiDisplayIndex_]);
    glUniform1i(uni_.baseTex, 2);
    glActiveTexture(GL_TEXTURE0);

    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    // Split on purpose: eglSwapBuffers blocks on vsync once the swap chain is
    // full, so folding it into "render cost" reports ~16ms of work at a steady
    // 60 FPS when the actual GPU work is a fraction of that.
    auto submitEndTime = std::chrono::high_resolution_clock::now();
    stats_.renderMs = std::chrono::duration<float, std::milli>(submitEndTime - renderStartTime).count();

    eglSwapBuffers(eglDisplay_, eglSurface_);

    auto swapEndTime = std::chrono::high_resolution_clock::now();
    stats_.swapMs = std::chrono::duration<float, std::milli>(swapEndTime - submitEndTime).count();

    // The AHardwareBuffer goes back to ImageReader as soon as this JNI call
    // returns, so the GPU must be done sampling from it by then.
    glFinish();

    frameCounter_++;
    auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    if (nowMs - lastFpsCalcTime_ >= 1000) {
        stats_.fps = (float)frameCounter_ * 1000.0f / (float)(nowMs - lastFpsCalcTime_);
        frameCounter_ = 0;
        lastFpsCalcTime_ = nowMs;
    }

    return true;
}

void GlRenderer::release() {
    // The worker touches the net and its buffers - it must be gone first.
    stopAiWorker();

    // GL object deletion needs a current context, otherwise it silently leaks.
    if (eglDisplay_ != EGL_NO_DISPLAY && eglContext_ != EGL_NO_CONTEXT &&
        eglSurface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);

        if (quadVao_ != 0) {
            glDeleteVertexArrays(1, &quadVao_);
            quadVao_ = 0;
        }
        if (quadVbo_ != 0) {
            glDeleteBuffers(1, &quadVbo_);
            quadVbo_ = 0;
        }
        if (oesPassProgram_ != 0) {
            glDeleteProgram(oesPassProgram_);
            oesPassProgram_ = 0;
        }
        if (yExtractProgram_ != 0) {
            glDeleteProgram(yExtractProgram_);
            yExtractProgram_ = 0;
        }
        if (aiFbo_ != 0) {
            glDeleteFramebuffers(1, &aiFbo_);
            aiFbo_ = 0;
        }
        if (baseTex_[0] != 0) {
            glDeleteTextures(2, baseTex_);
            baseTex_[0] = 0;
            baseTex_[1] = 0;
        }
        if (captureTex_ != 0) {
            glDeleteTextures(1, &captureTex_);
            captureTex_ = 0;
        }
        if (yHiTex_ != 0) {
            glDeleteTextures(1, &yHiTex_);
            yHiTex_ = 0;
        }
        if (detectTex_ != 0) {
            glDeleteTextures(1, &detectTex_);
            detectTex_ = 0;
        }
    }

    espcnEngine_.release();
    readbackBuffer_.clear();
    detectBuffer_.clear();
    aiInput_.clear();
    aiOutput_.clear();
    aiTexWidth_ = 0;
    aiTexHeight_ = 0;
    hasAiPair_ = false;

    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }

    if (nativeWindow_) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }
    hasGeometry_ = false;
}

} // namespace retroai
