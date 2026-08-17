#include "box_blur.h"

#include <algorithm>

namespace retroai {

void boxBlurClamped(const uint8_t* src, int width, int height, int radius,
                    std::vector<float>& dst, std::vector<float>& scratch) {
    const size_t n = (size_t)width * height;
    if (!src || width <= 0 || height <= 0 || radius < 1) return;
    dst.assign(n, 0.0f);
    scratch.assign(n, 0.0f);

    const float invH = 1.0f / (float)(2 * radius + 1);
    for (int y = 0; y < height; ++y) {
        const uint8_t* row = &src[(size_t)y * width];
        // Running sum seeded with the clamped left edge, then slid across.
        float sum = (float)row[0] * (float)(radius + 1);
        for (int x = 1; x <= radius && x < width; ++x) sum += (float)row[x];
        if (width <= radius) sum += (float)row[width - 1] * (float)(radius + 1 - width);
        for (int x = 0; x < width; ++x) {
            scratch[(size_t)y * width + x] = sum * invH;
            sum -= (float)row[std::max(0, x - radius)];
            sum += (float)row[std::min(width - 1, x + radius + 1)];
        }
    }
    for (int x = 0; x < width; ++x) {
        float sum = scratch[x] * (float)(radius + 1);
        for (int y = 1; y <= radius && y < height; ++y) sum += scratch[(size_t)y * width + x];
        if (height <= radius) sum += scratch[(size_t)(height - 1) * width + x] * (float)(radius + 1 - height);
        for (int y = 0; y < height; ++y) {
            dst[(size_t)y * width + x] = sum * invH * (1.0f / 255.0f);
            sum -= scratch[(size_t)std::max(0, y - radius) * width + x];
            sum += scratch[(size_t)std::min(height - 1, y + radius + 1) * width + x];
        }
    }
}

} // namespace retroai
