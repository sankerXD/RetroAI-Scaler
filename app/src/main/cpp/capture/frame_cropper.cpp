#include "frame_cropper.h"
#include "../common/log.h"
#include <cmath>
#include <algorithm>

namespace retroai {

// Standard console target aspect ratios
static const float ASPECT_GBA = 240.0f / 160.0f; // 1.50
static const float ASPECT_GBC = 160.0f / 144.0f; // 1.111
static const float ASPECT_4_3 = 4.0f / 3.0f;     // 1.333
static const float ASPECT_8_7 = 8.0f / 7.0f;     // 1.143

FrameCropper::FrameCropper() = default;
FrameCropper::~FrameCropper() = default;

void FrameCropper::setScreenDimensions(int screenWidth, int screenHeight) {
    screenWidth_ = screenWidth;
    screenHeight_ = screenHeight;
    currentCrop_ = {0, 0, screenWidth, screenHeight};
    targetCrop_ = currentCrop_;
}

void FrameCropper::setAutoCropEnabled(bool enabled) {
    autoCropEnabled_ = enabled;
    if (!enabled) {
        currentCrop_ = {0, 0, screenWidth_, screenHeight_};
    }
}

void FrameCropper::forceRecalibrate() {
    recalibrateRequested_ = true;
    frameCount_ = 0;
    std::fill(temporalDiffAccum_.begin(), temporalDiffAccum_.end(), 0);
}

CropRect FrameCropper::detectGameViewport(const uint8_t* downsampledY, int dsWidth, int dsHeight) {
    if (!autoCropEnabled_ || dsWidth <= 0 || dsHeight <= 0) {
        return {0, 0, screenWidth_, screenHeight_};
    }

    size_t totalPixels = dsWidth * dsHeight;
    if (previousFrameY_.size() != totalPixels) {
        previousFrameY_.resize(totalPixels, 0);
        temporalDiffAccum_.resize(totalPixels, 0);
        recalibrateRequested_ = true;
    }

    // 1. Accumulate frame-to-frame temporal difference (Motion / Active game detection)
    for (size_t i = 0; i < totalPixels; ++i) {
        uint8_t diff = std::abs((int)downsampledY[i] - (int)previousFrameY_[i]);
        // Temporal decay + accumulation
        temporalDiffAccum_[i] = (uint8_t)((temporalDiffAccum_[i] * 7 + diff) / 8);
        previousFrameY_[i] = downsampledY[i];
    }
    frameCount_++;

    // Only recalculate bounds every ~15 frames or on forced recalibrate
    if (frameCount_ % 15 == 0 || recalibrateRequested_) {
        recalibrateRequested_ = false;

        // 2. Scan lines from Left, Top, Right, Bottom towards center
        int dsMinX = 0, dsMaxX = dsWidth - 1;
        int dsMinY = 0, dsMaxY = dsHeight - 1;

        const uint8_t BLACK_THRESHOLD = 12;
        const uint8_t DIFF_THRESHOLD = 3;

        // Top edge scan
        for (int y = 0; y < dsHeight / 2; ++y) {
            int activeCount = 0;
            for (int x = 0; x < dsWidth; ++x) {
                int idx = y * dsWidth + x;
                if (downsampledY[idx] > BLACK_THRESHOLD || temporalDiffAccum_[idx] > DIFF_THRESHOLD) {
                    activeCount++;
                }
            }
            if (activeCount > dsWidth * 0.15f) {
                dsMinY = y;
                break;
            }
        }

        // Bottom edge scan
        for (int y = dsHeight - 1; y >= dsHeight / 2; --y) {
            int activeCount = 0;
            for (int x = 0; x < dsWidth; ++x) {
                int idx = y * dsWidth + x;
                if (downsampledY[idx] > BLACK_THRESHOLD || temporalDiffAccum_[idx] > DIFF_THRESHOLD) {
                    activeCount++;
                }
            }
            if (activeCount > dsWidth * 0.15f) {
                dsMaxY = y;
                break;
            }
        }

        // Left edge scan
        for (int x = 0; x < dsWidth / 2; ++x) {
            int activeCount = 0;
            for (int y = dsMinY; y <= dsMaxY; ++y) {
                int idx = y * dsWidth + x;
                if (downsampledY[idx] > BLACK_THRESHOLD || temporalDiffAccum_[idx] > DIFF_THRESHOLD) {
                    activeCount++;
                }
            }
            if (activeCount > (dsMaxY - dsMinY) * 0.15f) {
                dsMinX = x;
                break;
            }
        }

        // Right edge scan
        for (int x = dsWidth - 1; x >= dsWidth / 2; --x) {
            int activeCount = 0;
            for (int y = dsMinY; y <= dsMaxY; ++y) {
                int idx = y * dsWidth + x;
                if (downsampledY[idx] > BLACK_THRESHOLD || temporalDiffAccum_[idx] > DIFF_THRESHOLD) {
                    activeCount++;
                }
            }
            if (activeCount > (dsMaxY - dsMinY) * 0.15f) {
                dsMaxX = x;
                break;
            }
        }

        // Project downsampled coordinates back to screen dimensions
        float scaleX = (float)screenWidth_ / (float)dsWidth;
        float scaleY = (float)screenHeight_ / (float)dsHeight;

        CropRect rawRect;
        rawRect.left = std::max(0, (int)(dsMinX * scaleX));
        rawRect.top = std::max(0, (int)(dsMinY * scaleY));
        rawRect.right = std::min(screenWidth_, (int)(dsMaxX * scaleX));
        rawRect.bottom = std::min(screenHeight_, (int)(dsMaxY * scaleY));

        // 3. Snap to closest console aspect ratio
        targetCrop_ = snapToConsoleAspectRatio(rawRect, screenWidth_, screenHeight_);
    }

    // 4. Temporal Exponential Moving Average (EMA) smoothing to eliminate jitter
    applyTemporalSmoothing(targetCrop_);
    return currentCrop_;
}

CropRect FrameCropper::snapToConsoleAspectRatio(const CropRect& rect, int fullWidth, int fullHeight) {
    if (rect.width() < fullWidth * 0.25f || rect.height() < fullHeight * 0.25f) {
        // Fallback to full screen if detected box is invalid
        return {0, 0, fullWidth, fullHeight};
    }

    float currentAr = rect.aspectRatio();
    float bestAr = currentAr;
    float minDelta = 0.15f; // Threshold for snapping

    const float candidates[] = {ASPECT_GBA, ASPECT_4_3, ASPECT_8_7, ASPECT_GBC};
    for (float candidate : candidates) {
        float delta = std::abs(currentAr - candidate);
        if (delta < minDelta) {
            minDelta = delta;
            bestAr = candidate;
        }
    }

    // Adjust crop box width/height around center to match target aspect ratio
    int centerX = (rect.left + rect.right) / 2;
    int centerY = (rect.top + rect.bottom) / 2;

    int newW = rect.width();
    int newH = (int)(newW / bestAr);

    if (newH > fullHeight) {
        newH = rect.height();
        newW = (int)(newH * bestAr);
    }

    CropRect snapped;
    snapped.left = std::max(0, centerX - newW / 2);
    snapped.top = std::max(0, centerY - newH / 2);
    snapped.right = std::min(fullWidth, snapped.left + newW);
    snapped.bottom = std::min(fullHeight, snapped.top + newH);
    return snapped;
}

void FrameCropper::applyTemporalSmoothing(const CropRect& target) {
    const float alpha = 0.15f; // Smoothing factor
    currentCrop_.left = (int)(currentCrop_.left * (1.0f - alpha) + target.left * alpha);
    currentCrop_.top = (int)(currentCrop_.top * (1.0f - alpha) + target.top * alpha);
    currentCrop_.right = (int)(currentCrop_.right * (1.0f - alpha) + target.right * alpha);
    currentCrop_.bottom = (int)(currentCrop_.bottom * (1.0f - alpha) + target.bottom * alpha);
}

} // namespace retroai
