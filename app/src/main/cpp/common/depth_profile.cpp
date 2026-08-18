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

void boxDepthField(const uint8_t* src, int width, int height, int radius,
                   std::vector<float>& dst) {
    if (!src || width <= 0 || height <= 0 || radius < 0) return;

    const size_t n = (size_t)width * (size_t)height;
    dst.assign(n, 0.0f);
    if (radius == 0) {
        for (size_t i = 0; i < n; ++i) dst[i] = (float)src[i] * (1.0f / 255.0f);
        return;
    }

    const int span = 2 * radius + 1;
    const float inv = 1.0f / (float)span;

    // Horizontal pass into dst, then vertical in place through a column
    // scratch. Both are running sums: the cost is two adds per pixel and does
    // not grow with the radius, which is what makes a 9x9 at 240x160 free
    // enough to sit on the inference worker.
    //
    // Edge CLAMPED, not zero padded. Zero padding would darken the border and
    // the lighting reads the gradient, so a padded edge draws a line round the
    // whole picture - the exact class of artefact this pass exists to avoid.
    std::vector<float> tmp((size_t)height, 0.0f);
    for (int y = 0; y < height; ++y) {
        const uint8_t* row = &src[(size_t)y * width];
        float sum = (float)row[0] * (float)(radius + 1);
        for (int x = 1; x <= radius; ++x) sum += (float)row[std::min(x, width - 1)];
        float* out = &dst[(size_t)y * width];
        for (int x = 0; x < width; ++x) {
            out[x] = sum * inv * (1.0f / 255.0f);
            sum -= (float)row[std::max(0, x - radius)];
            sum += (float)row[std::min(width - 1, x + radius + 1)];
        }
    }

    for (int x = 0; x < width; ++x) {
        float sum = dst[(size_t)x] * (float)(radius + 1);
        for (int y = 1; y <= radius; ++y) {
            sum += dst[(size_t)std::min(y, height - 1) * width + x];
        }
        for (int y = 0; y < height; ++y) {
            tmp[(size_t)y] = sum * inv;
            sum -= dst[(size_t)std::max(0, y - radius) * width + x];
            sum += dst[(size_t)std::min(height - 1, y + radius + 1) * width + x];
        }
        for (int y = 0; y < height; ++y) dst[(size_t)y * width + x] = tmp[(size_t)y];
    }
}

} // namespace retroai
