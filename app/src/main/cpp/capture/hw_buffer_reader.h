#pragma once

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>

namespace retroai {

class HwBufferReader {
public:
    HwBufferReader();
    ~HwBufferReader();

    bool init(EGLDisplay display, EGLContext context);
    void release();

    // Zero-copy bind AHardwareBuffer to GL_TEXTURE_EXTERNAL_OES
    GLuint bindHardwareBufferToTexture(AHardwareBuffer* buffer, int& outWidth, int& outHeight);

private:
    EGLDisplay eglDisplay_{EGL_NO_DISPLAY};
    EGLContext eglContext_{EGL_NO_CONTEXT};
    EGLImageKHR currentEglImage_{EGL_NO_IMAGE_KHR};
    GLuint externalTextureId_{0};

    // EGL function pointers
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID_{nullptr};
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR_{nullptr};
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR_{nullptr};
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES_{nullptr};
};

} // namespace retroai
