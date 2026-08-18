#pragma once

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>
#include "../capture/frame_cropper.h"
#include "../ai/espcn_inference.h"
#include <chrono>
#include "ui_panels.h"

namespace retroai {

struct PerformanceStats {
    float fps{0.0f};
    float captureMs{0.0f};
    float aiMs{0.0f};
    float renderMs{0.0f};   // GPU pass submission, excludes the vsync wait
    float swapMs{0.0f};     // eglSwapBuffers, i.e. how long we waited on vsync
};

/**
 * Screen-space rectangle, top-left origin, pixel units.
 */
struct RectI {
    int x{0};
    int y{0};
    int w{0};
    int h{0};

    bool valid() const { return w > 0 && h > 0; }
};

class GlRenderer {
public:
    GlRenderer();
    ~GlRenderer();

    bool init(ANativeWindow* window, int screenWidth, int screenHeight);
    void release();

    // Render single frame given external texture ID from AHardwareBuffer
    bool renderFrame(GLuint externalTexId, int frameWidth, int frameHeight);
    bool ensureEglContextCurrent();

    /**
     * CRITICAL SAFETY PATH: paints the whole overlay fully transparent.
     * Called by the frame watchdog and on shutdown so a stalled capture
     * pipeline can never leave an opaque sheet covering the device.
     */
    bool clearOverlay();

    /**
     * source: where RetroArch renders its small 1x window (screen pixels).
     * output: where the enhanced image is painted (screen pixels).
     * The two must not be the same region - the source area is punched out
     * of the output so we never capture our own output (feedback loop).
     */
    void setGeometry(const RectI& source, const RectI& output, bool showSourceGuide,
                     bool protectSource);

    /**
     * Grabs the capture window at exactly native resolution for the training
     * corpus. The source region is clean in both capture modes - whole-screen
     * discards our output over it, single-app never mirrors us - so no
     * blanking is needed and the grab is instant.
     */
    void requestNativeCapture() { nativeCaptureRequested_ = true; }
    bool takeCapturedFrame(std::vector<uint8_t>& out, int& w, int& h);

    /** Starts the capture-mode probe; result arrives a handful of frames later. */
    void requestCaptureModeProbe();
    /** -1 while still unknown, 0 single-app, 1 whole-screen. */
    int captureModeResult() const { return captureMode_; }

    void setRenderConfig(
        bool isAiEnabled,
        int consoleNativeWidth,
        int consoleNativeHeight,
        float scanlineIntensity,
        float lcdGridIntensity
    );

    /** Hands the ncnn model bytes to the inference engine. */
    bool loadEspcnModel(const char* paramText,
                        const unsigned char* binData,
                        size_t binSize,
                        int scaleFactor,
                        bool preferGpu,
                        int inChannels,
                        int outChannels);

    /**
     * Paused = the target app is not on screen. The overlay is wiped once and
     * then left alone; no further GPU work happens until it resumes.
     */
    void setPaused(bool paused);
    bool isPaused() const { return paused_; }

    /**
     * Measures where the emulator is actually drawing, instead of trusting a
     * config value. Painting is suppressed for a few frames first so our own
     * output does not end up in the frame being measured.
     */
    void requestSourceDetection(int expectedWidth, int expectedHeight);
    bool getDetectedRect(RectI& out) const;

    /** 0 none, 1 aperture grille, 2 shadow mask, 3 slot mask. */
    void setMaskType(int type);

    /** Scale2x-style edge reconstruction instead of the network. */
    void setPixelEdgeEnabled(bool enabled);

    /** Depth-driven lighting on top of whatever upscaler is running. */
    void setHd2dEnabled(bool enabled) { hd2dEnabled_ = enabled; }
    void setHd2dStrength(float strength) { hd2dStrength_ = strength; }
    /** Tilt-shift focus band, 0..1. Needs no depth, so it stands alone. */
    void setDofStrength(float strength) { dofStrength_ = strength; }
    void setBloomStrength(float strength) { bloomStrength_ = strength; }


    /**
     * Drops the network; rendering falls back to the shader path.
     *
     * MUST stop the worker first. The worker runs inference on the net without
     * holding any lock, so deleting it underneath is a use-after-free: ncnn
     * ends up locking a destroyed mutex and the process aborts. This showed up
     * as "switching away from Ultra crashes" because its inference window is
     * long enough to almost always be mid-forward when the switch happens.
     */
    void unloadEspcn() {
        stopAiWorker();
        espcnEngine_.release();
        espcnFailureStreak_ = 0;
        hasAiPair_ = false;
    }

    PerformanceStats getStats() const { return stats_; }

    /**
     * Which backend the network actually ended up on: -1 no network loaded,
     * 0 ncnn on CPU, 1 ncnn on Vulkan.
     *
     * Surfaced in the HUD because "is this running on the GPU?" is otherwise
     * only answerable by grepping logcat at load time, and the answer decides
     * whether a frame cost is expected or a fallback nobody noticed.
     */
    int aiBackend() const {
        if (!espcnEngine_.isReady()) return -1;
        return espcnEngine_.isUsingGpu() ? 1 : 0;
    }
    void updateAiTime(float timeMs) { stats_.aiMs = timeMs; }

    EGLDisplay getEglDisplay() const { return eglDisplay_; }
    EGLContext getEglContext() const { return eglContext_; }

private:
    bool initEGL(ANativeWindow* window);
    void initGLResources();
    GLuint compileShader(GLenum type, const char* source);
    GLuint createProgram(const char* vertSource, const char* fragSource);
    void drawTransparent();

    /** Allocates/resizes the native-res base textures and the hi-res Y texture. */
    bool ensureAiTextures();
    /** Submits a frame to the AI worker and uploads any finished result. */
    bool runEspcnPass(GLuint externalTexId, int frameWidth, int frameHeight);
    void startAiWorker();
    void stopAiWorker();
    void aiWorkerLoop();

    EGLDisplay eglDisplay_{EGL_NO_DISPLAY};
    EGLSurface eglSurface_{EGL_NO_SURFACE};
    EGLContext eglContext_{EGL_NO_CONTEXT};
    ANativeWindow* nativeWindow_{nullptr};

    int screenWidth_{0};
    int screenHeight_{0};

    // Geometry (screen pixels, top-left origin)
    RectI sourceRect_{};
    RectI outputRect_{};
    bool showSourceGuide_{false};
    /** False under single-app capture: our output is allowed over the source. */
    bool protectSource_{true};
    /** HD-2D lights the picture with the depth net instead of replacing it. */
    bool hd2dEnabled_{false};
    float hd2dStrength_{0.5f};
    /** Tilt-shift depth of field. Independent of HD-2D: it needs no depth. */
    float dofStrength_{0.0f};
    /** Highlight bleed. A lens effect like the focus band - no depth needed. */
    float bloomStrength_{0.0f};
    /**
     * How much of each new depth estimate is taken. 1 would be no averaging.
     *
     * THIS NUMBER NOW MEANS SOMETHING ELSE THAN IT USED TO, and reading it as
     * the old one is how it would get mis-tuned again.
     *
     * It used to sit in a trade documented here as one-dimensional and
     * unavoidable: average less and the estimator's wobble shows, average more
     * and the light trails a moving sprite, six burst sequences pricing 0.35 at
     * lag 0.0358 / flicker 1.94% against 1.00 at lag 0.0032 / flicker 4.79%,
     * plus four measured attempts to escape it that all failed.
     *
     * They failed for one reason. The wobble was not the estimator disagreeing
     * with itself - it was SHIFT-VARIANCE, a strided head and a PixelShuffle
     * tail making the depth a function of which lattice the content landed on,
     * with an anchored mip read in the shading doing the same at a period of
     * eight. A deterministic oscillation cannot be filtered out of a signal:
     * averaging trades its amplitude for lag at exactly one rate, which is the
     * straight line all four experiments kept landing on. There was never a
     * second dimension to search. Both sources are fixed at source now (down=1,
     * and boxDepthField in place of the mip).
     *
     * So the averaging is no longer suppressing estimator noise. Measured on 33
     * burst sequences, camera-still pairs only, the noise it would have to
     * suppress fell from 0.01457 to 0.00618, and switching the blend off
     * entirely shimmers LESS than the old network did at the old 0.35. What is
     * left for it to do is a different and legitimate job: two-frame sprite
     * animations - flowers swaying, torches - genuinely change the depth, the
     * network is RIGHT to follow them, and without any blend the lighting
     * visibly snaps between the two states.
     *
     * 0.55 was settled on the device against that, walking down from no
     * averaging until the snapping stopped reading. It is the largest rate that
     * achieves it, which is the right end to pick from: the measured shimmer is
     * nearly flat from 0.35 to 0.55 (0.00491 to 0.00567, indistinguishable by
     * eye and confirmed so on the handheld) while the lag it adds doubles
     * across that range, 16 ms against 37 ms.
     *
     * The adaptive per-pixel rate is gone. It keyed on |new - old|, the very
     * quantity it was denoising, so it could never separate the estimator's
     * wobble from the scene moving - and now that the network is equivariant,
     * that difference is real signal every time.
     *
     * NOT the whole of the lag on screen, and tuning this to chase the rest
     * will not work: the picture is the live capture and the depth is whatever
     * inference last finished, so the two are 20-40 ms apart no matter what
     * this is set to. See section 7.
     */
    static constexpr float kDepthSmoothing = 0.55f;
    std::vector<uint8_t> depthHistory_{};

    // ---- scroll compensation -------------------------------------------
    /**
     * The depth is a HELD sample of a field that never stops moving.
     *
     * A result lands every ~20 ms and the display runs at 60 Hz, so between
     * results the picture scrolls and the depth does not: the offset between
     * them ramps up and drops back every time a result arrives, which is the
     * shading appearing to advance a pixel and fall back. It is not the blend
     * rate (turning the blend off changes it 7%) and not the 8-bit history (a
     * float history scores identically) - it is the hold itself. Simulated
     * over the real schedule, per-frame shading residual 0.0158 held against
     * 0.0003 compensated, 56x. Model repo: scripts/probe_latency.py.
     *
     * So the fragment reads the depth at uv MINUS the scroll accumulated since
     * the frame that depth describes. Written by the worker under aiMutex_,
     * copied out by the render thread when it collects a result.
     *
     * This is only correct because the network is equivariant to integer
     * translation (13.11). Shifting depth assumes D(shift(I)) == shift(D(I)),
     * and on the pre-down=1 network that was false by 0.027 at odd shifts.
     */
    float aiScrollVx_{0.0f};      // native texels per millisecond
    float aiScrollVy_{0.0f};
    float aiScrollConf_{0.0f};    // 0 = do not compensate
    bool aiWantScroll_{false};
    std::chrono::steady_clock::time_point aiJobTime_{};
    std::chrono::steady_clock::time_point aiResultFrameTime_{};

    /** Render-thread copies, valid for the depth currently on the GPU. */
    float depthScrollVx_{0.0f};
    float depthScrollVy_{0.0f};
    float depthScrollConf_{0.0f};
    std::chrono::steady_clock::time_point depthFrameTime_{};
    /**
     * Ceiling on the compensation, native texels.
     *
     * The velocity is extrapolated from the interval BEFORE the depth's frame,
     * so a direction change is mispredicted for one inference interval - which
     * is no worse than the uncompensated behaviour, but it must not be allowed
     * to grow. At a normal walking scroll the shift is 1-3 texels; anything
     * near this ceiling is a bad estimate, and smearing the lighting halfway
     * across the screen would be far more visible than the artefact being
     * removed.
     */
    static constexpr float kMaxScrollShift = 12.0f;
    /** Below this a pair is not measuring the scroll, so it must not set it. */
    static constexpr float kScrollTrust = 0.55f;
    /** How much of each trusted measurement enters the running velocity. */
    static constexpr float kScrollBlend = 0.5f;
    /** Unreadable for this long and the velocity starts decaying toward zero. */
    static constexpr float kScrollHoldMs = 120.0f;
    /** Diagnostic accumulators for the applied-shift trace. */
    float shiftMin_{1e9f}, shiftMax_{-1e9f}, shiftSum_{0.0f}, shiftAge_{0.0f};
    int shiftN_{0};
    bool hasGeometry_{false};
    bool paused_{false};
    bool pausedFrameDrawn_{false};

    // Source auto-detection
    enum class DetectState { Idle, Blanking, Measuring };
    DetectState detectState_{DetectState::Idle};

    /**
     * Probe that decides whether our own overlay is inside the capture.
     *
     * The consent dialog offers whole-screen and single-app capture, there is
     * no API to force the latter, and the two need different geometry - so the
     * service has to know which one the user picked. The captured-content
     * callbacks looked like the answer but fire identically in both modes.
     *
     * So test the property that actually matters instead: paint a marker,
     * read the capture back, paint nothing, read it back again. If the marker
     * shows up in the capture we are inside it, which means whole-screen.
     * Differential rather than absolute, so whatever the emulator happens to
     * be drawing underneath cannot be mistaken for the marker.
     */
    enum class ProbeState { Idle, MarkerOn, MarkerOff, Done };
    ProbeState probeState_{ProbeState::Idle};
    int probeFrames_{0};
    float probeSampleOn_[3]{};
    float probeSampleOff_[3]{};
    /** -1 unknown, 0 single-app (we are not captured), 1 whole-screen. */
    int captureMode_{-1};
    int detectBlankFrames_{0};
    int detectExpectedW_{240};
    int detectExpectedH_{160};
    GLuint detectTex_{0};
    RectI detectedRect_{};
    bool detectedValid_{false};
    std::vector<uint8_t> detectBuffer_{};
    void runDetectionPass(GLuint externalTexId, int frameWidth, int frameHeight);

    bool nativeCaptureRequested_{false};
    bool capturedValid_{false};
    int capturedW_{0};
    int capturedH_{0};
    std::vector<uint8_t> capturedFrame_{};
    GLuint captureTex_{0};
    void runNativeCapture(GLuint externalTexId, int frameWidth, int frameHeight);

    /** Renders the whole captured frame into detectTex_ and reads it back. */
    bool readReducedFrame(GLuint externalTexId, int frameWidth, int frameHeight);
    /** Paints the probe marker, or clears if `on` is false. */
    void drawProbeMarker(bool on);
    /** Mean RGB of the marker's corner of the last reduced readback. */
    void sampleProbeRegion(float outRgb[3]) const;
    void runCaptureModeProbe(GLuint externalTexId, int frameWidth, int frameHeight);

    // Render Settings
    bool isAiEnabled_{true};
    int consoleNativeWidth_{240};
    int consoleNativeHeight_{160};
    float scanlineIntensity_{0.0f};
    float lcdGridIntensity_{0.0f};
    bool pixelEdgeEnabled_{false};
    int maskType_{0};

    // GL Programs and Buffers
    GLuint quadVao_{0};
    GLuint quadVbo_{0};
    GLuint oesPassProgram_{0};

    // ---- NCNN ESPCN Y-channel path ----
    //
    // Inference costs ~20ms on this class of SoC, far past the 16.6ms frame
    // budget, so it runs on its own thread. The composite must not mix a fresh
    // RGB frame with a stale reconstructed luminance (that shows up as colour
    // fringing on anything that moves), so the base RGB is captured into a
    // native-res texture alongside the luminance that was sent to the network,
    // and the pair is only swapped in once inference for that exact frame is
    // done. Cost: the ESPCN path is one frame behind. The shader path samples
    // the live external texture and stays lag-free.
    GLuint yExtractProgram_{0};
    GLuint aiFbo_{0};
    GLuint baseTex_[2]{0, 0};   // native-res RGB, ping-pong
    GLuint yHiTex_{0};          // ESPCN output, scale * native
    /**
     * True when yHiTex_ holds a depth field rather than a picture.
     *
     * The two want opposite sampling: the picture must stay hard-edged and is
     * read at level 0 only, while the shading needs a genuinely blurred depth
     * through textureLod. Only the depth gets a mipmap chain and a mipmapping
     * filter, so neither can pick up the other's treatment.
     */
    bool yHiIsDepth_{false};
    int aiSubmitIndex_{1};
    int aiDisplayIndex_{0};
    bool hasAiPair_{false};

    int aiTexWidth_{0};
    int aiTexHeight_{0};
    int aiScale_{3};
    std::vector<uint8_t> readbackBuffer_{};
    int espcnFailureStreak_{0};

    // Worker thread state (guarded by aiMutex_)
    std::thread aiThread_;
    std::mutex aiMutex_;
    std::condition_variable aiCv_;
    bool aiShutdown_{false};
    bool aiJobPending_{false};
    bool aiResultReady_{false};
    bool aiBusy_{false};
    bool aiFailed_{false};
    int aiJobWidth_{0};
    int aiJobHeight_{0};
    float aiLastMs_{0.0f};
    std::vector<uint8_t> aiInput_{};
    std::vector<uint8_t> aiOutput_{};
    /**
     * Interface-panel mask for the frame the worker just processed, produced
     * alongside the depth from the same input buffer. Detection runs on the
     * worker rather than the render thread: it is CPU work on a native-res
     * frame, and the render thread holds the pipeline lock across a whole GPU
     * frame (see AGENT.md 10.3a).
     */
    std::vector<uint8_t> aiUiMask_{};
    bool aiWantUiMask_{false};
    UiPanelFinder uiPanels_{};
    GLuint uiMaskTex_{0};
    /**
     * The depth's per-row average, subtracted before the lighting takes its
     * gradient - a horizontal band is a feature constant across x, so this
     * removes the whole class exactly. One column wide. See depth_profile.h.
     *
     * Half float, not 8-bit: it is a smooth field, so quantising it arrives as
     * contour banding across flat areas rather than as noise.
     */
    GLuint depthBaseTex_{0};
    std::vector<float> depthBase_{};
    /**
     * The blurred depth the lighting takes its gradient from, native
     * resolution, computed on the inference worker.
     *
     * Replaces a textureLod() read of the depth's mip chain. Mip level 3 is an
     * 8x8 average on a grid anchored to the texture, so it does not translate
     * with the picture - measured as a period-8 wobble reaching 0.019 of the
     * shading range per pixel of scroll, against 0.00006 for this. See
     * boxDepthField in common/depth_profile.h for the full account.
     */
    GLuint depthWideTex_{0};
    std::vector<float> depthWide_{};

    /** No mask yet means no lighting yet - see the uHd2d gate. */
    bool hasUiMask_{false};

    // Cached uniform locations (glGetUniformLocation per frame is wasteful)
    struct {
        GLint screenSize{-1};
        GLint captureSize{-1};
        GLint sourceRect{-1};
        GLint outputRect{-1};
        GLint nativeRes{-1};
        GLint aiScale{-1};
        GLint protectSource{-1};
        GLint neuralRgb{-1};
        GLint showDepth{-1};
        GLint hd2d{-1};
        GLint lightDir{-1};
        GLint relief{-1};
        GLint occlusion{-1};
        GLint hazeCap{-1};
        GLint hazeKnee{-1};
        GLint shadeRadius{-1};
        GLint depthBaseTex{-1};
        GLint depthWideTex{-1};
        GLint depthShift{-1};
        GLint depthBias{-1};
        GLint shadeStrength{-1};
        GLint uiMaskTex{-1};
        GLint dofStrength{-1};
        GLint dofCentre{-1};
        GLint dofBand{-1};
        GLint dofRadius{-1};
        GLint bloomStrength{-1};
        GLint bloomThreshold{-1};
        GLint bloomRadius{-1};
        GLint aiEnabled{-1};
        GLint scanline{-1};
        GLint lcdGrid{-1};
        GLint showGuide{-1};
        GLint externalTex{-1};
        GLint yHiTex{-1};
        GLint baseTex{-1};
        GLint useNeuralY{-1};
        GLint pixelEdge{-1};
        GLint maskType{-1};
    } uni_;

    struct {
        GLint externalTex{-1};
        GLint captureSize{-1};
        GLint sourceRect{-1};
        GLint nativeRes{-1};
    } yUni_;

    EspcnInference espcnEngine_{};
    PerformanceStats stats_{};

    // Timing tracking
    int frameCounter_{0};
    int64_t lastFpsCalcTime_{0};
};

} // namespace retroai
