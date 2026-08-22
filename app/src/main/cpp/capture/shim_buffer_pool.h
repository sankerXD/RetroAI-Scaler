#pragma once

#include <android/hardware_buffer.h>
#include <cstdint>

/**
 * Turns the libretro shim's CPU pixels into an AHardwareBuffer the existing
 * renderer can sample.
 *
 * The whole renderer and every shader take GL_TEXTURE_EXTERNAL_OES input, so
 * the cheapest correct way in is the path capture already uses:
 * AHardwareBuffer -> EGLImage -> external texture. Adding a sampler2D route for
 * shim frames would touch every shader and the shader gate, to arrive at the
 * same pixels.
 *
 * Three buffers, allocated once. AHardwareBuffer_allocate costs hundreds of
 * microseconds to milliseconds and fragments over time, so doing it per frame -
 * which is what the design book originally called for - would cost more than
 * the copy it was meant to serve. Three is enough that the one being written is
 * never the one the GPU is reading.
 *
 * Usage flags are the most conservative pair that works, deliberately:
 * CPU_WRITE_OFTEN | GPU_SAMPLED_IMAGE. Helio parts in this project have a
 * history of scrambling anything more adventurous (CaptureBridge.kt:21-24).
 */
class ShimBufferPool {
public:
    ~ShimBufferPool();

    /**
     * Converts one frame into the next buffer in the ring and returns it.
     * `pixelFormat` is the libretro value: 0 = 0RGB1555, 1 = XRGB8888,
     * 2 = RGB565. `srcPitch` is bytes per source row.
     *
     * Returns nullptr on any failure; the caller keeps the overlay transparent
     * rather than showing a stale frame.
     */
    AHardwareBuffer* fill(const uint8_t* src, int width, int height,
                          int srcPitch, int pixelFormat);

    void release();

private:
    static constexpr int kCount = 3;

    bool ensure(int width, int height);

    AHardwareBuffer* buffers_[kCount] = {nullptr, nullptr, nullptr};
    int              next_   = 0;
    int              width_  = 0;
    int              height_ = 0;
};
