#pragma once

#include <cstdint>
#include <vector>

namespace retroai {

/**
 * Edge-clamped separable box mean of an 8-bit map, output normalised to 0..1.
 *
 * Edge clamping is the entire reason this exists rather than a mip level. A
 * window centred near the last row still averages a different set of rows than
 * one centred higher up, so its value keeps changing right up to the border. A
 * mip wide enough to hold the same support is only a couple of texels across,
 * and under clamp-to-edge such a texture is CONSTANT outside the middle of the
 * image - which is exactly where the correction is needed. See depthDetail()
 * in gl_renderer.cpp.
 *
 * @param scratch reused between calls; sized internally.
 */
void boxBlurClamped(const uint8_t* src, int width, int height, int radius,
                    std::vector<float>& dst, std::vector<float>& scratch);

} // namespace retroai
