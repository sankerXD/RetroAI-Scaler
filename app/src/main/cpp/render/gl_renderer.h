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
    void setGeometry(const RectI& source, const RectI& output, bool showSourceGuide);

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
                        bool preferGpu);

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
    bool hasGeometry_{false};
    bool paused_{false};
    bool pausedFrameDrawn_{false};

    // Source auto-detection
    enum class DetectState { Idle, Blanking, Measuring };
    DetectState detectState_{DetectState::Idle};
    int detectBlankFrames_{0};
    int detectExpectedW_{240};
    int detectExpectedH_{160};
    GLuint detectTex_{0};
    RectI detectedRect_{};
    bool detectedValid_{false};
    std::vector<uint8_t> detectBuffer_{};
    void runDetectionPass(GLuint externalTexId, int frameWidth, int frameHeight);

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

    // Cached uniform locations (glGetUniformLocation per frame is wasteful)
    struct {
        GLint screenSize{-1};
        GLint captureSize{-1};
        GLint sourceRect{-1};
        GLint outputRect{-1};
        GLint nativeRes{-1};
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
