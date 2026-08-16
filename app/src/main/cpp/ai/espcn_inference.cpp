#include "espcn_inference.h"
#include "../common/log.h"
#include <chrono>
#include <cstring>

#if __has_include(<ncnn/net.h>)
#include <ncnn/net.h>
#include <ncnn/mat.h>
#define HAS_NCNN 1
#else
#define HAS_NCNN 0
#endif

namespace retroai {

EspcnInference::EspcnInference() = default;

EspcnInference::~EspcnInference() {
    release();
}

bool EspcnInference::loadModel(const char* paramText,
                               const unsigned char* binData,
                               size_t binSize,
                               int scaleFactor,
                               bool preferGpu) {
#if HAS_NCNN
    release();

    if (!paramText || !binData || binSize == 0) {
        ALOGE("loadModel: empty model data");
        return false;
    }

    scaleFactor_ = scaleFactor > 0 ? scaleFactor : 3;
    paramText_.assign(paramText);
    weightBlob_.assign(binData, binData + binSize);

    auto* net = new ncnn::Net();

    // GPU inference where it exists. The deep "ultra" model is ~30x the
    // arithmetic of the small ones - far past what two big cores can do inside
    // a frame - but a modern mobile GPU chews through it. Falls back to CPU
    // automatically when no Vulkan device is present (older handhelds).
    int gpuCount = ncnn::get_gpu_count();
    useVulkan_ = preferGpu && gpuCount > 0;
    net->opt.use_vulkan_compute = useVulkan_;
    if (useVulkan_) {
        net->set_vulkan_device(0);
        ALOGI("ncnn Vulkan enabled (%d GPU device(s))", gpuCount);
    }

    net->opt.num_threads = 2;           // the two Cortex-A76 big cores
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = true;
    net->opt.use_packing_layout = true;
    net->opt.lightmode = true;

    int retParam = net->load_param_mem(paramText_.c_str());
    if (retParam != 0) {
        ALOGE("load_param_mem failed: %d", retParam);
        delete net;
        return false;
    }

    // load_model returns the number of bytes consumed, not 0 on success.
    int consumed = net->load_model(weightBlob_.data());
    if (consumed <= 0) {
        ALOGE("load_model failed (consumed=%d of %zu bytes)", consumed, binSize);
        delete net;
        return false;
    }

    ncnnNet_ = net;
    isReady_ = true;
    ALOGI("NCNN ESPCN loaded: scale=%dx, %zu weight bytes (%d consumed), backend=%s",
          scaleFactor_, binSize, consumed, useVulkan_ ? "Vulkan GPU" : "CPU FP16/NEON");
    return true;
#else
    (void)paramText; (void)binData; (void)binSize; (void)scaleFactor;
    ALOGW("Built without NCNN - ESPCN unavailable, GPU shader path only.");
    return false;
#endif
}

bool EspcnInference::processLuminance(
    const uint8_t* inY,
    int inWidth,
    int inHeight,
    uint8_t* outY,
    int outWidth,
    int outHeight,
    float& outInferenceTimeMs
) {
    outInferenceTimeMs = 0.0f;
    if (!isReady_ || !inY || !outY || inWidth <= 0 || inHeight <= 0) {
        return false;
    }
    if (outWidth != inWidth * scaleFactor_ || outHeight != inHeight * scaleFactor_) {
        ALOGE("processLuminance: size mismatch %dx%d -> %dx%d (scale %d)",
              inWidth, inHeight, outWidth, outHeight, scaleFactor_);
        return false;
    }

#if HAS_NCNN
    auto startTime = std::chrono::high_resolution_clock::now();

    auto* net = static_cast<ncnn::Net*>(ncnnNet_);

    ncnn::Mat inMat = ncnn::Mat::from_pixels(inY, ncnn::Mat::PIXEL_GRAY, inWidth, inHeight);
    const float normVals[1] = {1.0f / 255.0f};
    inMat.substract_mean_normalize(nullptr, normVals);

    ncnn::Extractor ex = net->create_extractor();
    ex.set_light_mode(true);
    ex.set_num_threads(2);
    ex.input("input_y", inMat);

    ncnn::Mat outMat;
    int ret = ex.extract("output_y", outMat);
    if (ret != 0 || outMat.w != outWidth || outMat.h != outHeight || outMat.c != 1) {
        ALOGE("ESPCN extract failed: ret=%d out=%dx%dx%d", ret, outMat.w, outMat.h, outMat.c);
        return false;
    }

    const float denormVals[1] = {255.0f};
    outMat.substract_mean_normalize(nullptr, denormVals);
    outMat.to_pixels(outY, ncnn::Mat::PIXEL_GRAY);

    auto endTime = std::chrono::high_resolution_clock::now();
    outInferenceTimeMs = std::chrono::duration<float, std::milli>(endTime - startTime).count();
    return true;
#else
    return false;
#endif
}

void EspcnInference::release() {
#if HAS_NCNN
    useVulkan_ = false;
    if (ncnnNet_) {
        delete static_cast<ncnn::Net*>(ncnnNet_);
        ncnnNet_ = nullptr;
    }
#endif
    weightBlob_.clear();
    weightBlob_.shrink_to_fit();
    paramText_.clear();
    isReady_ = false;
}

} // namespace retroai
