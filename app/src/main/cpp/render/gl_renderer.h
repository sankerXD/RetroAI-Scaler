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
    /**
     * Previous depth map, for the temporal average. 0.35 keeps roughly three
     * inference frames in flight: enough to settle the estimator's flicker,
     * short enough that the lighting still follows the scene.
     */
    static constexpr float kDepthSmoothing = 0.35f;
    std::vector<uint8_t> depthHistory_{};
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
     * The depth's low-frequency component, subtracted before the lighting
     * takes its gradient. Half float, not 8-bit: quantising it costs 0.43% of
     * lambert on average and 2.1% at the 99th percentile, and it is a smooth
     * field, so that error arrives as contour banding across flat areas rather
     * than as noise. Half float takes it to 0.03%.
     */
    GLuint depthBaseTex_{0};
    std::vector<float> depthBase_{};
    std::vector<float> blurScratch_{};

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
        GLint shadeRadius{-1};
        GLint depthBaseTex{-1};
        GLint shadeStrength{-1};
        GLint uiMaskTex{-1};
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
