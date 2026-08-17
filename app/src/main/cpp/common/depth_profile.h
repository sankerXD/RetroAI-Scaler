#pragma once

#include <cstdint>
#include <vector>

namespace retroai {

/**
 * The depth's per-row average, smoothed along y. Subtracted from the depth
 * before the HD-2D lighting takes its gradient.
 *
 * WHY A ROW PROFILE AND NOT A 2D BLUR
 *
 * A horizontal band is, by definition, a feature that is CONSTANT ACROSS X.
 * Removing each row's own mean removes every such feature exactly, and leaves
 * everything that varies along the row untouched. That is a much sharper tool
 * than a wide 2D average, which only removes a smooth global ramp: measured
 * over the corpus, a radius-40 box high-pass left the row-to-row brightness
 * slope at 0.0090 - no better than no correction at all (0.0080) - while the
 * row profile takes it to 0.0031, and the 99th percentile from 0.099 to 0.022.
 *
 * The network needs this because its output carries a strong, content-
 * independent vertical structure: flat at 0.22 for the top two thirds, then
 * ramping to 0.77, and not smoothly - there are kinks around rows 110-115 and
 * 135-140 that are four times the typical slope. A wide average removes the
 * ramp but not the kinks, and each kink is what becomes a band.
 *
 * Cost: 13% of the lighting variation, since genuine full-width horizontal
 * relief goes with it. The global "higher is farther" cue is not lost - it is
 * carried by the distance haze, which reads the absolute depth.
 *
 * @param dst  one entry per row, normalised to 0..1.
 */
void rowDepthProfile(const uint8_t* src, int width, int height, int radius,
                     std::vector<float>& dst);

} // namespace retroai
