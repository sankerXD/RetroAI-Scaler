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

/**
 * The blurred depth the lighting takes its gradient from: an edge-clamped
 * separable box mean at native resolution, normalised to 0..1.
 *
 * THIS REPLACES A MIP READ, AND THE DIFFERENCE IS THE WHOLE POINT
 *
 * The shading used to get this from textureLod(depth, uv, 3.0). Mip level 3 is
 * an 8x8 average on a grid ANCHORED TO THE TEXTURE, reconstructed bilinearly -
 * so as the game scrolls, the content moves and the boxes do not. The field it
 * returns therefore does not translate with the scene, it changes shape, and
 * the bilinear reconstruction puts a slope discontinuity on every 8-pixel cell
 * boundary at a fixed place on screen. The lighting reads the GRADIENT of this,
 * which is precisely the quantity those discontinuities corrupt.
 *
 * Measured (RetroAI-Model scripts/probe_shift.py) by shifting a frame, shading
 * it, and shifting the answer back - a shift-invariant filter returns what it
 * started with:
 *
 *     shift    1      2      3      4      8     12     16
 *     mip    .008   .014   .017   .019   .001   .018   .001
 *     box    .00006 .00007 .00008 .00006 .00006 .00006 .00005
 *
 * A clean period-8 triangle against a flat line 250x lower. That triangle is
 * the wave the player sees running up the picture while walking, and no amount
 * of temporal averaging touches it: the mip is regenerated from the depth AFTER
 * the frame-to-frame average, so it is downstream of every existing remedy.
 *
 * A sliding box translates exactly with its input, so the defect is gone by
 * construction rather than reduced. It is also CHEAPER in the fragment: five
 * plain texture() taps instead of five textureLod(), and the mip chain no
 * longer has to be built every time a depth map arrives.
 *
 * Section 13.5 already reached this conclusion once, for the row profile: "the
 * wide average cannot come from the mip chain, this was tried and it failed...
 * so the average is computed on the CPU and uploaded whole". That verdict was
 * never carried across to the read the shading actually uses.
 *
 * Runs on the inference worker, on the buffer the network already produced -
 * never on the render thread, which holds the pipeline mutex across the whole
 * GPU frame (section 10.3a).
 *
 * @param radius  4, matching the 9x9 window hd2d.py has always modelled.
 * @param dst     width*height floats, row major, 0..1.
 */
void boxDepthField(const uint8_t* src, int width, int height, int radius,
                   std::vector<float>& dst);

} // namespace retroai
