#include "hw_buffer_reader.h"
#include "../common/log.h"

namespace retroai {

HwBufferReader::HwBufferReader() = default;

HwBufferReader::~HwBufferReader() {
    release();
}

bool HwBufferReader::init(EGLDisplay display, EGLContext context) {
    eglDisplay_ = display;
    eglContext_ = context;

    // Query EGL extension function pointers
    eglGetNativeClientBufferANDROID_ = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)
            eglGetProcAddress("eglGetNativeClientBufferANDROID");
    eglCreateImageKHR_ = (PFNEGLCREATEIMAGEKHRPROC)
            eglGetProcAddress("eglCreateImageKHR");
    eglDestroyImageKHR_ = (PFNEGLDESTROYIMAGEKHRPROC)
            eglGetProcAddress("eglDestroyImageKHR");
    glEGLImageTargetTexture2DOES_ = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
            eglGetProcAddress("glEGLImageTargetTexture2DOES");

    if (!eglGetNativeClientBufferANDROID_ || !eglCreateImageKHR_ ||
        !eglDestroyImageKHR_ || !glEGLImageTargetTexture2DOES_) {
        ALOGE("Failed to get EGL/GLES extension function pointers for AHardwareBuffer zero-copy");
        return false;
    }

    // Generate external texture
    glGenTextures(1, &externalTextureId_);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTextureId_);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    ALOGI("HwBufferReader initialized successfully with zero-copy texture ID: %u", externalTextureId_);
    return true;
}

GLuint HwBufferReader::bindHardwareBufferToTexture(AHardwareBuffer* buffer, int& outWidth, int& outHeight) {
    if (!buffer || eglDisplay_ == EGL_NO_DISPLAY) {
        return 0;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    outWidth = static_cast<int>(desc.width);
    outHeight = static_cast<int>(desc.height);

    // Destroy previous EGLImage if existed
    if (currentEglImage_ != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR_(eglDisplay_, currentEglImage_);
        currentEglImage_ = EGL_NO_IMAGE_KHR;
    }

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID_(buffer);
    if (!clientBuffer) {
        ALOGE("eglGetNativeClientBufferANDROID failed");
        return 0;
    }

    const EGLint imageAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };

    currentEglImage_ = eglCreateImageKHR_(
        eglDisplay_,
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer,
        imageAttrs
    );

    if (currentEglImage_ == EGL_NO_IMAGE_KHR) {
        ALOGE("eglCreateImageKHR failed with error: 0x%x", eglGetError());
        return 0;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, externalTextureId_);
    glEGLImageTargetTexture2DOES_(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)currentEglImage_);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    return externalTextureId_;
}

void HwBufferReader::release() {
    if (currentEglImage_ != EGL_NO_IMAGE_KHR && eglDisplay_ != EGL_NO_DISPLAY) {
        eglDestroyImageKHR_(eglDisplay_, currentEglImage_);
        currentEglImage_ = EGL_NO_IMAGE_KHR;
    }
    if (externalTextureId_ != 0) {
        glDeleteTextures(1, &externalTextureId_);
        externalTextureId_ = 0;
    }
}

} // namespace retroai
