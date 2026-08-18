#include "scroll_estimator.h"

#include <algorithm>
#include <cstdlib>
#include <limits>

namespace retroai {
namespace {

/**
 * Sum of absolute differences over a subsampled central window.
 *
 * Subsampled because this runs on the inference worker next to a 20 ms forward
 * pass and must not be a second cost centre: every third pixel of the middle
 * half is a few thousand samples, which is enough to separate an exact match
 * from a wrong one by orders of magnitude. Central, because the leading edge is
 * where content scrolls IN - it has no counterpart in the previous frame and
 * would score every offset badly.
 *
 * `budget` stops the walk once a candidate is already worse than the best so
 * far. Most offsets are eliminated in a handful of rows.
 */
long sadAt(const uint8_t* prev, const uint8_t* cur, int width, int height,
           int channels, int dx, int dy, long budget) {
    const int marginX = width / 4;
    const int marginY = height / 4;
    const int step = 3;

    long total = 0;
    for (int y = marginY; y < height - marginY; y += step) {
        const int sy = y - dy;
        if (sy < 0 || sy >= height) continue;
        const uint8_t* rowCur = &cur[(size_t)y * width * channels];
        const uint8_t* rowPrev = &prev[(size_t)sy * width * channels];
        for (int x = marginX; x < width - marginX; x += step) {
            const int sx = x - dx;
            if (sx < 0 || sx >= width) continue;
            const uint8_t* a = &rowCur[(size_t)x * channels];
            const uint8_t* b = &rowPrev[(size_t)sx * channels];
            for (int c = 0; c < channels; ++c) {
                total += std::abs((int)a[c] - (int)b[c]);
            }
        }
        if (total >= budget) return total;   // already lost
    }
    return total;
}

} // namespace

ScrollEstimate estimateScroll(const uint8_t* prev, const uint8_t* cur,
                              int width, int height, int channels,
                              int searchRadius, int seedX, int seedY) {
    ScrollEstimate out;
    if (!prev || !cur || width <= 8 || height <= 8 || channels <= 0) return out;

    const long none = sadAt(prev, cur, width, height, channels, 0, 0,
                            std::numeric_limits<long>::max());

    // A still picture. Nothing to compensate, and saying so confidently is
    // right - it is not a failure to find motion, it is an absence of motion.
    if (none == 0) {
        out.confidence = 1.0f;
        return out;
    }

    // How much the frame changes when it DOES move one texel. Without this
    // reference, "still with a flower animating" and "cannot be read" score
    // the same zero: the best vector is (0,0) in both cases, so
    // 1 - best/none is 1 - 1 = 0 even though the first answer is exactly
    // right. Measured on the device standing still, the confidence flipped
    // between 1.00 and 0.00 depending on whether a sprite happened to be
    // mid-animation.
    const long perTexel = sadAt(cur, cur, width, height, channels, 0, 1,
                                std::numeric_limits<long>::max());
    if (perTexel > 0 && none * 10 < perTexel) {
        // Far less change than a single texel of movement would cause, so
        // whatever moved, the scene did not.
        out.confidence = 1.0f;
        return out;
    }

    long best = none;
    int bestX = 0, bestY = 0;

    // The seed first, and an exact hit ends it. Scroll velocity is continuous,
    // so the previous answer is usually this answer, and the common case then
    // costs one SAD instead of a few hundred.
    if (seedX != 0 || seedY != 0) {
        const long s = sadAt(prev, cur, width, height, channels, seedX, seedY, best);
        if (s < best) { best = s; bestX = seedX; bestY = seedY; }
        if (best == 0) {
            out.dx = (float)bestX;
            out.dy = (float)bestY;
            out.confidence = 1.0f;
            return out;
        }
    }

    for (int dy = -searchRadius; dy <= searchRadius; ++dy) {
        for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
            if (dx == 0 && dy == 0) continue;
            if (dx == seedX && dy == seedY) continue;
            const long s = sadAt(prev, cur, width, height, channels, dx, dy, best);
            if (s < best) {
                best = s;
                bestX = dx;
                bestY = dy;
                if (best == 0) goto done;
            }
        }
    }
done:

    out.dx = (float)bestX;
    out.dy = (float)bestY;

    // Confidence is how much of the frame's own change this vector explains.
    // An exact translation drives the residual to zero and scores 1; a frame
    // whose change is a fade, an effect, or several layers moving differently
    // leaves the residual near what it was and scores near 0.
    //
    // Deliberately NOT a threshold on the residual. The right response to a
    // partly-explained frame is to compensate partly, and a confidence that
    // slides is also what stops the shift snapping on and off between frames -
    // which would be a new flicker in place of the one being removed.
    out.confidence = (float)(1.0 - (double)best / (double)none);
    if (out.confidence < 0.0f) out.confidence = 0.0f;
    if (out.confidence > 1.0f) out.confidence = 1.0f;
    return out;
}

} // namespace retroai
