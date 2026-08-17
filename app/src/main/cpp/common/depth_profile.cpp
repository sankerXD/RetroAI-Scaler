#include "depth_profile.h"

#include <algorithm>

namespace retroai {

void rowDepthProfile(const uint8_t* src, int width, int height, int radius,
                     std::vector<float>& dst) {
    if (!src || width <= 0 || height <= 0 || radius < 0) return;

    std::vector<float> mean((size_t)height, 0.0f);
    const float invW = 1.0f / (float)width;
    for (int y = 0; y < height; ++y) {
        const uint8_t* row = &src[(size_t)y * width];
        int sum = 0;
        for (int x = 0; x < width; ++x) sum += row[x];
        mean[y] = (float)sum * invW * (1.0f / 255.0f);
    }

    // Smoothed along y with the same radius the shading reads the depth at, so
    // what gets subtracted is the profile of the blur, not of the raw map.
    // Edge-clamped: the profile must keep changing right down to the last row,
    // which is where the bias it is removing is strongest.
    dst.assign((size_t)height, 0.0f);
    if (radius == 0) { dst = mean; return; }
    const float invH = 1.0f / (float)(2 * radius + 1);
    float sum = mean[0] * (float)(radius + 1);
    for (int y = 1; y <= radius && y < height; ++y) sum += mean[y];
    if (height <= radius) sum += mean[height - 1] * (float)(radius + 1 - height);
    for (int y = 0; y < height; ++y) {
        dst[y] = sum * invH;
        sum -= mean[std::max(0, y - radius)];
        sum += mean[std::min(height - 1, y + radius + 1)];
    }
}

} // namespace retroai
