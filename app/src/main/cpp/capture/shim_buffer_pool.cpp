#include "shim_buffer_pool.h"

#include "../common/log.h"

#include <cstring>

namespace {

/**
 * Widen a 5- or 6-bit channel to 8 bits by REPLICATING the high bits into the
 * low ones, never by shifting left.
 *
 * A left shift maps the maximum (31 or 63) to 248 or 252 instead of 255, so
 * white stops being white and the whole image sits slightly dark. On a still
 * frame that is close to invisible, which is exactly what makes it dangerous:
 * it would systematically shift every frame the network ever sees away from the
 * distribution it was trained on. The gate 3 comparison against RetroArch's own
 * native screenshot is what pins this down - with replication it is bit-exact,
 * and a shift would fail it loudly.
 */
inline uint8_t expand5(uint32_t c) { return static_cast<uint8_t>((c << 3) | (c >> 2)); }
inline uint8_t expand6(uint32_t c) { return static_cast<uint8_t>((c << 2) | (c >> 4)); }

} // namespace

ShimBufferPool::~ShimBufferPool() {
    release();
}

void ShimBufferPool::release() {
    for (auto& buf : buffers_) {
        if (buf) {
            AHardwareBuffer_release(buf);
            buf = nullptr;
        }
    }
    width_ = height_ = 0;
    next_ = 0;
}

bool ShimBufferPool::ensure(int width, int height) {
    if (width == width_ && height == height_ && buffers_[0]) {
        return true;
    }
    release();
    if (width <= 0 || height <= 0) {
        return false;
    }

    AHardwareBuffer_Desc desc = {};
    desc.width  = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage  = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN |
                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;

    for (int i = 0; i < kCount; ++i) {
        if (AHardwareBuffer_allocate(&desc, &buffers_[i]) != 0 || !buffers_[i]) {
            ALOGE("ShimBufferPool: allocating %dx%d buffer %d failed", width, height, i);
            release();
            return false;
        }
    }

    width_  = width;
    height_ = height;
    next_   = 0;
    ALOGI("ShimBufferPool: %d buffers of %dx%d RGBA8888", kCount, width, height);
    return true;
}

AHardwareBuffer* ShimBufferPool::fill(const uint8_t* src, int width, int height,
                                      int srcPitch, int pixelFormat) {
    if (!src || !ensure(width, height)) {
        return nullptr;
    }

    AHardwareBuffer* buf = buffers_[next_];
    next_ = (next_ + 1) % kCount;

    AHardwareBuffer_Desc desc = {};
    AHardwareBuffer_describe(buf, &desc);

    void* mapped = nullptr;
    // Lock can wait on the GPU still reading this buffer. That wait is on the
    // frame-source thread, never inside retro_run, so the worst it costs is our
    // own latency - the emulated game is on the other side of a socket and
    // cannot be held up by anything here.
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                             -1, nullptr, &mapped) != 0 || !mapped) {
        ALOGE("ShimBufferPool: lock failed");
        return nullptr;
    }

    auto* dstBase = static_cast<uint8_t*>(mapped);
    // stride is in PIXELS, not bytes, and is not always the width: drivers pad
    // rows for alignment, and writing width*4 per row into a padded buffer
    // shears the image diagonally.
    const size_t dstPitch = static_cast<size_t>(desc.stride) * 4;

    for (int y = 0; y < height; ++y) {
        const uint8_t* srcRow = src + static_cast<size_t>(y) * srcPitch;
        uint8_t*       dstRow = dstBase + static_cast<size_t>(y) * dstPitch;

        switch (pixelFormat) {
            case 2: { // RGB565
                const auto* p = reinterpret_cast<const uint16_t*>(srcRow);
                for (int x = 0; x < width; ++x) {
                    const uint32_t v = p[x];
                    dstRow[x * 4 + 0] = expand5((v >> 11) & 0x1F);
                    dstRow[x * 4 + 1] = expand6((v >> 5) & 0x3F);
                    dstRow[x * 4 + 2] = expand5(v & 0x1F);
                    dstRow[x * 4 + 3] = 0xFF;
                }
                break;
            }
            case 0: { // 0RGB1555
                const auto* p = reinterpret_cast<const uint16_t*>(srcRow);
                for (int x = 0; x < width; ++x) {
                    const uint32_t v = p[x];
                    dstRow[x * 4 + 0] = expand5((v >> 10) & 0x1F);
                    dstRow[x * 4 + 1] = expand5((v >> 5) & 0x1F);
                    dstRow[x * 4 + 2] = expand5(v & 0x1F);
                    dstRow[x * 4 + 3] = 0xFF;
                }
                break;
            }
            case 1: { // XRGB8888, little-endian, so the bytes are B G R X
                for (int x = 0; x < width; ++x) {
                    dstRow[x * 4 + 0] = srcRow[x * 4 + 2];
                    dstRow[x * 4 + 1] = srcRow[x * 4 + 1];
                    dstRow[x * 4 + 2] = srcRow[x * 4 + 0];
                    dstRow[x * 4 + 3] = 0xFF;
                }
                break;
            }
            default:
                AHardwareBuffer_unlock(buf, nullptr);
                ALOGE("ShimBufferPool: unknown libretro pixel format %d", pixelFormat);
                return nullptr;
        }
    }

    AHardwareBuffer_unlock(buf, nullptr);
    return buf;
}
