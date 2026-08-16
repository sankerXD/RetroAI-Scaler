#pragma once

#include <cstdint>
#include <vector>

namespace retroai {

struct CropRect {
    int left{0};
    int top{0};
    int right{0};
    int bottom{0};

    int width() const { return right - left; }
    int height() const { return bottom - top; }
    float aspectRatio() const { return height() > 0 ? (float)width() / (float)height() : 1.0f; }
};

class FrameCropper {
public:
    FrameCropper();
    ~FrameCropper();

    void setScreenDimensions(int screenWidth, int screenHeight);
    void setAutoCropEnabled(bool enabled);
    void forceRecalibrate();

    // Analyzes downsampled frame luminance/variance to compute true game viewport
    CropRect detectGameViewport(const uint8_t* downsampledY, int dsWidth, int dsHeight);

    // Get current smoothed crop rectangle (in full screen coordinate space)
    CropRect getCurrentCropRect() const { return currentCrop_; }

private:
    CropRect snapToConsoleAspectRatio(const CropRect& rect, int fullWidth, int fullHeight);
    void applyTemporalSmoothing(const CropRect& targetRect);

    bool autoCropEnabled_{true};
    int screenWidth_{0};
    int screenHeight_{0};

    CropRect currentCrop_{};
    CropRect targetCrop_{};

    // Temporal difference buffers
    std::vector<uint8_t> previousFrameY_{};
    std::vector<uint8_t> temporalDiffAccum_{};
    int frameCount_{0};
    bool recalibrateRequested_{true};
};

} // namespace retroai
