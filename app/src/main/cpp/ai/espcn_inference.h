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
    bool loadModel(const char* paramText,
                   const unsigned char* binData,
                   size_t binSize,
                   int scaleFactor,
                   bool preferGpu);

    bool isUsingGpu() const { return useVulkan_; }

    void release();

    /**
     * inY  : native-resolution luminance, inWidth * inHeight bytes
     * outY : must hold (inWidth * scale) * (inHeight * scale) bytes
     * Returns false if no model is loaded - the caller should then fall back to
     * the GPU shader path instead of showing a blank frame.
     */
    bool processLuminance(
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

private:
    int scaleFactor_{3};
    bool isReady_{false};
    bool useVulkan_{false};

    void* ncnnNet_{nullptr};
    std::vector<unsigned char> weightBlob_{};
    std::string paramText_{};
};

} // namespace retroai
