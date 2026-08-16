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
    static int frameLogCount = 0;
    if (++frameLogCount % 300 == 1) {
        ALOGI("frame #%d rendered success=%d (%dx%d, tex=%u)",
              frameLogCount, success, frameW, frameH, texId);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeSetGeometry(
    JNIEnv* /* env */,
    jobject /* this */,
    jint srcX, jint srcY, jint srcW, jint srcH,
    jint outX, jint outY, jint outW, jint outH,
    jboolean showSourceGuide
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (gRenderer) {
        gRenderer->setGeometry(
            RectI{srcX, srcY, srcW, srcH},
            RectI{outX, outY, outW, outH},
            showSourceGuide == JNI_TRUE
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

JNIEXPORT jboolean JNICALL
Java_com_retroai_scaler_jni_NativeBridge_nativeGetPerformanceStats(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray statsOut
) {
    std::lock_guard<std::mutex> lock(gPipelineMutex);
    if (!gRenderer || !statsOut) return JNI_FALSE;

    if (env->GetArrayLength(statsOut) < 5) return JNI_FALSE;

    PerformanceStats stats = gRenderer->getStats();
    jfloat buffer[5] = {stats.fps, stats.captureMs, stats.aiMs, stats.renderMs, stats.swapMs};
    env->SetFloatArrayRegion(statsOut, 0, 5, buffer);
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
