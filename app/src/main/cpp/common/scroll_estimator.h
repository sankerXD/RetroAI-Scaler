#pragma once

#include <cstdint>

namespace retroai {

struct ScrollEstimate {
    /** Native texels the picture moved, previous frame -> current. */
    float dx{0.0f};
    float dy{0.0f};
    /** 0 = do not use this, 1 = the match was exact. */
    float confidence{0.0f};
};

/**
 * How far the picture scrolled between two native-resolution frames.
 *
 * WHY THIS IS EXACT HERE AND NOT AN ESTIMATE
 *
 * Section 12.1 dismissed motion vectors because an emulator hands us a
 * composited RGB frame and nothing else. That is true, and irrelevant, because
 * in this domain the vector can be RECOVERED rather than guessed:
 *
 *   - a GBA's background scroll registers are integers, so a scrolling layer
 *     moves a whole number of pixels;
 *   - the capture window is snapped to an integer multiple of the native size
 *     and sampled at block centres, so what comes back is the emulator's own
 *     pixels with no resampling anywhere in the path.
 *
 * So for the correct integer offset the two frames are BIT-IDENTICAL over the
 * scrolling layer, and the sum of absolute differences is not merely minimal,
 * it is zero. That makes the residual a free confidence signal: a match either
 * explains the frame or it does not, and there is no threshold to tune between
 * "close" and "wrong".
 *
 * WHAT IT IS FOR
 *
 * The depth is a zero-order sample of a field that never stops moving: a
 * result lands every ~20 ms, the display runs at 60 Hz, and between results the
 * picture scrolls while the depth does not. Simulated over the real schedule,
 * that leaves a per-frame shading residual of 0.0158 against 0.0003 once the
 * held depth is shifted by the scroll since its own frame - a 56x difference,
 * and the thing the player sees as the shading trying to advance a pixel and
 * falling back. Model repo: scripts/probe_latency.py.
 *
 * This only became correct to do once the depth network was made equivariant
 * to integer translation (down=1, section 13.11). Shifting the depth assumes
 * D(shift(I)) == shift(D(I)); on the old network that was false by 0.027 at odd
 * shifts, so the compensation would have been fighting the network.
 *
 * ONLY THE CAMERA. A single global vector cannot describe sprites moving
 * against the scroll, and does not try to: those blocks raise the residual,
 * which lowers the confidence, and a low confidence means no compensation
 * rather than a wrong one.
 *
 * @param prev,cur  interleaved 8-bit, `channels` per pixel, row major.
 * @param seedX,seedY  where to look first - pass the previous answer. Scroll
 *        velocity is continuous, so the seed is usually right, and an exact hit
 *        ends the search immediately.
 */
ScrollEstimate estimateScroll(const uint8_t* prev, const uint8_t* cur,
                              int width, int height, int channels,
                              int searchRadius, int seedX, int seedY);

} // namespace retroai
