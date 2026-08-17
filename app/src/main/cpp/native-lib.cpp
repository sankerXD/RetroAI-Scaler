#include <jni.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <atomic>
#include <memory>
#include <mutex>
#include "common/log.h"
#include "common/cpu_affinity.h"
#include "capture/hw_buffer_reader.h"
#include "capture/frame_cropper.h"
#include "render/gl_renderer.h"

using namespace retroai;

static std::unique_ptr<GlRenderer> gRenderer = nullptr;
static std::unique_ptr<HwBufferReader> gHwBufferReader = nullptr;
static std::unique_ptr<FrameCropper> gFrameCropper = nullptr;
static std::mutex gPipelineMutex;

// Set to false the moment teardown begins so in-flight capture callbacks
// bail out instead of piling up behind the mutex.
static std::atomic<bool> gPipelineActive{false};

/**
 * HUD counters, published by the render thread and read by the main thread.
 *
 * These deliberately live OUTSIDE the renderer and outside gPipelineMutex.
 * The capture thread holds that mutex for the whole GPU frame - EGL bind,
 * EGLImage import, draw and eglSwapBuffers - so anything on the main thread
 * that takes it to read five floats is queueing behind GPU work at 60-120 Hz.
 * On an Adreno 840 / 144 Hz handheld that starved the main thread past the
 * 5 s input-dispatch deadline: opening the floating menu started a 500 ms
 * stats poll, the poll blocked, and the next tap ANR'd - which also meant the
 * ESPCN engines could never be selected, because the tap that selects them
 * was never dispatched.
 *
 * Keeping the snapshot in file scope (rather than reading gRenderer without
 * the lock) is what makes the lock-free read safe: the reader never touches
 * the renderer, so it cannot race nativeRelease() deleting it.
 */
static std::atomic<float> gStatFps{0.0f};
static std::atomic<float> gStatCaptureMs{0.0f};
static std::atomic<float> gStatAiMs{0.0f};
static std::atomic<float> gStatRenderMs{0.0f};
static std::atomic<float> gStatSwapMs{0.0f};
/** -1 no network, 0 ncnn on CPU, 1 ncnn on Vulkan. */
static std::atomic<int> gStatAiBackend{-1};

/** Called from the render thread, which already owns the pipeline mutex. */
static void publishStats(const PerformanceStats& s, int aiBackend) {
    gStatFps.store(s.fps, std::memory_order_relaxed);
    gStatCaptureMs.store(s.captureMs, std::memory_order_relaxed);
    gStatAiMs.store(s.aiMs, std::memory_order_relaxed);
    gStatRenderMs.store(s.renderMs, std::memory_order_relaxed);
    gStatSwapMs.store(s.swapMs, std::memory_order_relaxed);
    gStatAiBackend.store(aiBackend, std::memory_order_relaxed);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jobject surface,
    jint screenWidth,
    jint screenHeight
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    ALOGI("nativeInit: Screen dimensions %dx%d", screenWidth, screenHeight);

    // Bind current and worker threads to Cortex-A76 performance cores (Helio G99)
    CpuAffinity::bindToBigCores();

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        ALOGE("Failed to obtain ANativeWindow from Surface");
        return JNI_FALSE;
    }

    gRenderer = std::make_unique<GlRenderer>();
    if (!gRenderer->init(window, screenWidth, screenHeight)) {
        ALOGE("Failed to initialize GlRenderer");
        gRenderer = nullptr;
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    // GlRenderer::init() releases the EGL context at the end, but
    // HwBufferReader::init() calls glGenTextures() which requires a current
    // context - without this the texture ID stays 0 and no frame ever lands.
    if (!gRenderer->ensureEglContextCurrent()) {
        ALOGE("Failed to re-acquire EGL context for HwBufferReader setup");
        gRenderer->release();
        gRenderer = nullptr;
        return JNI_FALSE;
    }

    gHwBufferReader = std::make_unique<HwBufferReader>();
    if (!gHwBufferReader->init(gRenderer->getEglDisplay(), gRenderer->getEglContext())) {
        ALOGE("Failed to initialize HwBufferReader - zero-copy path unavailable");
        gHwBufferReader = nullptr;
        gRenderer->release();
        gRenderer = nullptr;
        return JNI_FALSE;
    }

    // Release EGL context so the capture worker thread can acquire it later
    eglMakeCurrent(gRenderer->getEglDisplay(), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    gFrameCropper = std::make_unique<FrameCropper>();
    gFrameCropper->setScreenDimensions(screenWidth, screenHeight);

    // Clear the HUD snapshot so a fresh session never shows the previous run's
    // numbers in the window before the first frame lands.
    publishStats(PerformanceStats{}, -1);

    gPipelineActive = true;
    ALOGI("Native pipeline initialized successfully.");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeProcessHardwareBuffer(
    JNIEnv* env,
    jobject /* this */,
    jobject hardwareBufferObj
) {
    if (!gPipelineActive.load() || !hardwareBufferObj) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gRenderer || !gHwBufferReader) {
        return JNI_FALSE;
    }

    AHardwareBuffer* hwBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObj);
    if (!hwBuffer) {
        ALOGE("nativeProcessHardwareBuffer: hwBuffer is NULL");
        return JNI_FALSE;
    }

    if (!gRenderer->ensureEglContextCurrent()) {
        static int eglFailCount = 0;
        if (++eglFailCount % 60 == 1) {
            ALOGE("nativeProcessHardwareBuffer: ensureEglContextCurrent failed!");
        }
        return JNI_FALSE;
    }

    int frameW = 0, frameH = 0;
    GLuint texId = gHwBufferReader->bindHardwareBufferToTexture(hwBuffer, frameW, frameH);
    if (texId == 0) {
        static int bindFailCount = 0;
        if (++bindFailCount % 60 == 1) {
            ALOGE("nativeProcessHardwareBuffer: bindHardwareBufferToTexture failed!");
        }
        // Keep the overlay invisible rather than showing a stale frame.
        gRenderer->clearOverlay();
        return JNI_FALSE;
    }

    bool success = gRenderer->renderFrame(texId, frameW, frameH);
    publishStats(gRenderer->getStats(), gRenderer->aiBackend());
    static int frameLogCount = 0;
    if (++frameLogCount % 300 == 1) {
        // paused is in here because renderFrame() also returns true while
        // paused - it draws one transparent frame and then does nothing.
        // "success=1" alone therefore says the pipeline is ticking, not that
        // anything was actually painted.
        ALOGI("frame #%d rendered success=%d paused=%d (%dx%d, tex=%u)",
              frameLogCount, success, gRenderer->isPaused() ? 1 : 0,
              frameW, frameH, texId);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetGeometry(
    JNIEnv* /* env */,
    jobject /* this */,
    jint srcX, jint srcY, jint srcW, jint srcH,
    jint outX, jint outY, jint outW, jint outH,
    jboolean showSourceGuide,
    jboolean protectSource
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setGeometry(
            RectI{srcX, srcY, srcW, srcH},
            RectI{outX, outY, outW, outH},
            showSourceGuide == JNI_TRUE,
            protectSource == JNI_TRUE
        );
    }
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetRenderConfig(
    JNIEnv* /* env */,
    jobject /* this */,
    jboolean isAiEnabled,
    jint consoleNativeWidth,
    jint consoleNativeHeight,
    jfloat scanlineIntensity,
    jfloat lcdGridIntensity
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setRenderConfig(
            isAiEnabled,
            consoleNativeWidth,
            consoleNativeHeight,
            scanlineIntensity,
            lcdGridIntensity
        );
    }
}

/**
 * Loads the ncnn ESPCN weights. The model bytes are read from assets on the
 * Java side, so no AAssetManager is needed down here.
 */
JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeLoadEspcnModel(
    JNIEnv* env,
    jobject /* this */,
    jstring paramText,
    jbyteArray binData,
    jint scaleFactor,
    jboolean preferGpu
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gRenderer || !paramText || !binData) return JNI_FALSE;

    const char* paramChars = env->GetStringUTFChars(paramText, nullptr);
    jsize binSize = env->GetArrayLength(binData);
    jbyte* binChars = env->GetByteArrayElements(binData, nullptr);

    bool ok = false;
    if (paramChars && binChars && binSize > 0) {
        ok = gRenderer->loadEspcnModel(
            paramChars,
            reinterpret_cast<const unsigned char*>(binChars),
            (size_t)binSize,
            scaleFactor,
            preferGpu == JNI_TRUE
        );
    }

    if (binChars) env->ReleaseByteArrayElements(binData, binChars, JNI_ABORT);
    if (paramChars) env->ReleaseStringUTFChars(paramText, paramChars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeUnloadEspcnModel(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->unloadEspcn();
        ALOGI("ESPCN unloaded - GPU shader upscaler active.");
    }
}

/**
 * Asks the renderer to measure where the emulator actually draws. The result is
 * picked up a few frames later with nativeGetDetectedRect().
 */
JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeRequestSourceDetection(
    JNIEnv* /* env */,
    jobject /* this */,
    jint expectedWidth,
    jint expectedHeight
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->requestSourceDetection(expectedWidth, expectedHeight);
    }
}

/** rectOut = [x, y, w, h] in screen pixels. False until a measurement lands. */
JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeGetDetectedRect(
    JNIEnv* env,
    jobject /* this */,
    jintArray rectOut
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gRenderer || !rectOut || env->GetArrayLength(rectOut) < 4) return JNI_FALSE;

    RectI rect{};
    if (!gRenderer->getDetectedRect(rect)) return JNI_FALSE;

    jint buffer[4] = {rect.x, rect.y, rect.w, rect.h};
    env->SetIntArrayRegion(rectOut, 0, 4, buffer);
    return JNI_TRUE;
}

/**
 * Pauses/resumes painting. Driven by the foreground-app monitor: the overlay
 * must not cover the launcher, Recents or any other app.
 */
JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetRenderPaused(
    JNIEnv* /* env */,
    jobject /* this */,
    jboolean paused
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setPaused(paused == JNI_TRUE);
        if (paused == JNI_TRUE) {
            gRenderer->clearOverlay();
        }
    }
}

/**
 * Safety valve: wipe the overlay to fully transparent. Called by the frame
 * watchdog when capture stalls, and whenever the pipeline pauses.
 */
JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeClearOverlay(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gRenderer) return JNI_FALSE;
    return gRenderer->clearOverlay() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Unbind the EGL context from the CALLING thread. The capture worker thread
 * must call this before it dies, otherwise the context stays current on a
 * dead thread and every later eglMakeCurrent (clear / teardown) fails with
 * EGL_BAD_ACCESS, leaking the context and the overlay's last frame.
 */
JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeDetachEglContext(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    EGLDisplay display = eglGetCurrentDisplay();
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        ALOGI("EGL context detached from capture thread.");
    }
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetMaskType(
    JNIEnv* /* env */,
    jobject /* this */,
    jint maskType
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setMaskType(maskType);
    }
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetPixelEdge(
    JNIEnv* /* env */,
    jobject /* this */,
    jboolean enabled
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setPixelEdgeEnabled(enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetAutoCrop(
    JNIEnv* /* env */,
    jobject /* this */,
    jboolean enabled
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gFrameCropper) {
        gFrameCropper->setAutoCropEnabled(enabled);
    }
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeRecalibrateCrop(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gFrameCropper) {
        gFrameCropper->forceRecalibrate();
        ALOGI("Auto-crop recalibration requested.");
    }
}

/**
 * Kicks off the probe that decides whether our overlay is inside the capture,
 * i.e. whether the user granted whole-screen or single-app capture. Frames have
 * to be flowing; the answer lands a handful of frames later.
 */
JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeRequestCaptureModeProbe(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) gRenderer->requestCaptureModeProbe();
}

/** -1 still unknown, 0 single-app, 1 whole-screen. */
JNIEXPORT jint JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeGetCaptureMode(
    JNIEnv* /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    return gRenderer ? (jint)gRenderer->captureModeResult() : (jint)-1;
}

JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeGetPerformanceStats(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray statsOut
) {
    // No gPipelineMutex here, and no gRenderer dereference - see the comment on
    // the gStat* atomics. This runs on the main thread every 500 ms while the
    // floating menu is open; taking the pipeline lock here is what ANR'd.
    if (!gPipelineActive.load(std::memory_order_relaxed) || !statsOut) return JNI_FALSE;

    if (env->GetArrayLength(statsOut) < 6) return JNI_FALSE;

    jfloat buffer[6] = {
        gStatFps.load(std::memory_order_relaxed),
        gStatCaptureMs.load(std::memory_order_relaxed),
        gStatAiMs.load(std::memory_order_relaxed),
        gStatRenderMs.load(std::memory_order_relaxed),
        gStatSwapMs.load(std::memory_order_relaxed),
        (jfloat)gStatAiBackend.load(std::memory_order_relaxed)
    };
    env->SetFloatArrayRegion(statsOut, 0, 6, buffer);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeRelease(
    JNIEnv* /* env */,
    jobject /* this */
) {
    gPipelineActive = false;
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    ALOGI("nativeRelease: Cleaning up resources");

    if (gRenderer) {
        // Last frame must be transparent so nothing is left covering the screen.
        gRenderer->clearOverlay();
    }
    if (gHwBufferReader) {
        gHwBufferReader->release();
        gHwBufferReader = nullptr;
    }
    if (gRenderer) {
        gRenderer->release();
        gRenderer = nullptr;
    }
    gFrameCropper = nullptr;
}

} // extern "C"
