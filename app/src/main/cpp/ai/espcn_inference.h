#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace retroai {

/**
 * NCNN ESPCN sub-pixel super-resolution on the Y (luminance) channel.
 *
 * The model is handed in as raw bytes (read from assets on the Java side), so
 * no AAssetManager plumbing is needed. NCNN's load_model() keeps pointers INTO
 * the weight buffer, so this class owns a copy for the net's whole lifetime.
 */
class EspcnInference {
public:
    EspcnInference();
    ~EspcnInference();

    /**
     * paramText must be a NUL-terminated ncnn .param document.
     * binData/binSize is the matching .bin blob.
     */
    /**
     * channels selects the model family, and with it the blob names the net
     * is driven through:
     *   1 - the ESPCN models, luminance only, "input_y" / "output_y"
     *   3 - RetroAI, full RGB, "input" / "output"
     * The two coexist: ESPCN stays the choice for weaker hardware, and the
     * shapes are incompatible, so one flag has to say which is loaded.
     */
    bool loadModel(const char* paramText,
                   const unsigned char* binData,
                   size_t binSize,
                   int scaleFactor,
                   bool preferGpu,
                   int inChannels = 1,
                   int outChannels = 1);

    bool isUsingGpu() const { return useVulkan_; }

    void release();

    /**
     * in  : native-resolution pixels, inWidth * inHeight * channels() bytes,
     *       interleaved (Y, or RGB)
     * out : must hold (inWidth * scale) * (inHeight * scale) * channels() bytes
     * Returns false if no model is loaded - the caller should then fall back to
     * the GPU shader path instead of showing a blank frame.
     */
    bool process(
        const uint8_t* inY,
        int inWidth,
        int inHeight,
        uint8_t* outY,
        int outWidth,
        int outHeight,
        float& outInferenceTimeMs
    );

    bool isReady() const { return isReady_; }
    int getScaleFactor() const { return scaleFactor_; }
    /**
     * In and out are separate because they stopped agreeing: ESPCN is 1 -> 1,
     * RetroAI 3 -> 3, and the depth model 3 -> 1 at scale 1. Assuming one
     * number for both is what would silently size a buffer wrong.
     */
    int inChannels() const { return inChannels_; }
    int outChannels() const { return outChannels_; }

private:
    int scaleFactor_{3};
    int inChannels_{1};
    int outChannels_{1};
    bool isReady_{false};
    bool useVulkan_{false};

    void* ncnnNet_{nullptr};
    std::vector<unsigned char> weightBlob_{};
    std::string paramText_{};
};

} // namespace retroai
