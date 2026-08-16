#include "cpu_affinity.h"
#include "log.h"
#include <pthread.h>
#include <cstdio>
#include <algorithm>

namespace retroai {

namespace {

// Reads cpuinfo_max_freq for one core; 0 when unavailable.
long readMaxFreqKHz(int coreId) {
    char path[128];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", coreId);
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    long khz = 0;
    if (fscanf(f, "%ld", &khz) != 1) khz = 0;
    fclose(f);
    return khz;
}

} // namespace

bool CpuAffinity::bindToBigCores() {
    // Do NOT assume "the last two cores": core numbering differs between SoCs
    // and between handhelds. Pick the actual highest-frequency cluster.
    const int numCores = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (numCores <= 0) return false;

    std::vector<long> freqs((size_t)numCores, 0);
    long maxFreq = 0;
    for (int i = 0; i < numCores; ++i) {
        freqs[(size_t)i] = readMaxFreqKHz(i);
        maxFreq = std::max(maxFreq, freqs[(size_t)i]);
    }

    if (maxFreq == 0) {
        // No cpufreq nodes readable (some vendors restrict them) - fall back to
        // the common big.LITTLE layout of big cores last.
        if (numCores >= 8) return bindToCores({numCores - 2, numCores - 1});
        if (numCores >= 4) return bindToCores({numCores - 2, numCores - 1});
        return false;
    }

    std::vector<int> bigCores;
    for (int i = 0; i < numCores; ++i) {
        if (freqs[(size_t)i] == maxFreq) bigCores.push_back(i);
    }
    if (bigCores.empty() || (int)bigCores.size() == numCores) {
        // Uniform cluster - binding would only reduce scheduling freedom.
        return false;
    }

    ALOGI("Big cluster detected: %zu core(s) @ %ld MHz (of %d cores)",
          bigCores.size(), maxFreq / 1000, numCores);
    return bindToCores(bigCores);
}

bool CpuAffinity::bindToCores(const std::vector<int>& coreIds) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int coreId : coreIds) {
        CPU_SET(coreId, &cpuset);
    }

    pid_t tid = gettid();
    int result = sched_setaffinity(tid, sizeof(cpu_set_t), &cpuset);
    if (result == 0) {
        ALOGI("Successfully bound thread %d to specified performance cores", tid);
        return true;
    } else {
        ALOGW("Failed to set cpu affinity for thread %d (errno: %d)", tid, errno);
        return false;
    }
}

bool CpuAffinity::resetAffinity() {
    int numCores = sysconf(_SC_NPROCESSORS_CONF);
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int i = 0; i < numCores; ++i) {
        CPU_SET(i, &cpuset);
    }
    return sched_setaffinity(gettid(), sizeof(cpu_set_t), &cpuset) == 0;
}

} // namespace retroai
