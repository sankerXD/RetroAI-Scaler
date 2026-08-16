#pragma once

#include <sched.h>
#include <unistd.h>
#include <vector>

namespace retroai {

class CpuAffinity {
public:
    // Bind current calling thread to MediaTek Helio G99 Cortex-A76 Big Cores (Cores 6 & 7)
    static bool bindToBigCores();

    // Bind current thread to specific core IDs
    static bool bindToCores(const std::vector<int>& coreIds);

    // Reset thread affinity to all available cores
    static bool resetAffinity();
};

} // namespace retroai
