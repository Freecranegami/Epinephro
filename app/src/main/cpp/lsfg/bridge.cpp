// C interface to the frame generation core, used by the Vulkan shim (sgsr.c) inside the game process.
// The core runs on the game's own device and queue ("single-device mode" of lsfg-vk-android).
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "extract.hpp"
#include "trans.hpp"
#include "mipmaps_replacement_spv.h"
#include "lsfg_3_1.hpp"
#include "lsfg_3_1p.hpp"

#define EXPORT extern "C" __attribute__((visibility("default")))
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "GuestHost", __VA_ARGS__)

namespace {
bool g_perf = true;
bool g_ready = false;

void setErr(char *err, int n, const std::string &m) {
    if (err && n > 0) { std::snprintf(err, static_cast<size_t>(n), "%s", m.c_str()); }
}

bool g_fp16 = false;
int g_fp16_used = 0, g_fp16_skipped = 0;

std::vector<uint8_t> loadShader(const std::string &name) {
    if (name == "mipmaps" || name == "p_mipmaps")
        return std::vector<uint8_t>(mipmaps_replacement_spv, mipmaps_replacement_spv + mipmaps_replacement_spv_len);
    std::vector<uint8_t> t = Extract::translateShader(Extract::getShader(name));
    if (g_fp16) {   // experimental: the DLL's own 16-bit SPIR-V, only if it exposes the same bindings as the 32-bit shader
        std::vector<uint8_t> r = Extract::getShaderFp16(name);
        std::string why = "no 16-bit variant in the DLL";
        if (!r.empty() && Extract::fp16Compatible(r, t, why)) { g_fp16_used++; return r; }
        g_fp16_skipped++;
        if (g_fp16_skipped <= 4) LOG("lsfg: half precision: %s stays 32-bit (%s)", name.c_str(), why.c_str());
    }
    return t;
}
}

// returns 0 on success
EXPORT int las_lsfg_init(void *gipa, void *instance, void *phys, void *dev, uint32_t qfam, void *queue,
                         int perf, float flowScale, int generated, int hdr, const char *dll, char *err, int errlen) {
    try {
        if (g_ready) return 0;
        Extract::setDllPath(dll ? dll : "");
        Extract::extractShaders();
        g_perf = perf != 0;
        { const char *f = getenv("LAS_LSFG_FP16"); g_fp16 = f && *f == '1'; }
        LOG("lsfg: DLL has %s", Extract::resourceSummary().c_str());
        auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa);
        auto in = reinterpret_cast<VkInstance>(instance);
        auto pd = reinterpret_cast<VkPhysicalDevice>(phys);
        auto d = reinterpret_cast<VkDevice>(dev);
        auto q = reinterpret_cast<VkQueue>(queue);
        if (g_perf)
            LSFG_3_1P::initializeExternal(get, in, pd, d, qfam, q, hdr != 0, flowScale, static_cast<uint64_t>(generated), loadShader);
        else
            LSFG_3_1::initializeExternal(get, in, pd, d, qfam, q, hdr != 0, flowScale, static_cast<uint64_t>(generated), loadShader);
        g_ready = true;
        if (g_fp16) LOG("lsfg: half precision: %d shaders use the DLL's 16-bit variant, %d stay 32-bit", g_fp16_used, g_fp16_skipped);
        return 0;
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    return -1;
}

// the images must be in GENERAL layout; returns the context id, or -1
EXPORT int las_lsfg_context(uint64_t in0, uint64_t in1, const uint64_t *outs, int nouts, uint32_t w, uint32_t h, int format,
                            char *err, int errlen) {
    try {
        std::vector<VkImage> o;
        for (int i = 0; i < nouts; i++) o.push_back(reinterpret_cast<VkImage>(outs[i]));
        VkExtent2D ex{ w, h };
        auto a = reinterpret_cast<VkImage>(in0), b = reinterpret_cast<VkImage>(in1);
        return g_perf ? LSFG_3_1P::createContextFromImages(a, b, o, ex, static_cast<VkFormat>(format))
                      : LSFG_3_1::createContextFromImages(a, b, o, ex, static_cast<VkFormat>(format));
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    return -1;
}

// runs the generation on the queue given at init; returns 0 on success
EXPORT int las_lsfg_present(int id, char *err, int errlen) {
    try {
        std::vector<int> none;
        if (g_perf) LSFG_3_1P::presentContext(id, -1, none);
        else LSFG_3_1::presentContext(id, -1, none);
        return 0;
    } catch (const std::exception &e) {
        setErr(err, errlen, e.what());
    } catch (...) {
        setErr(err, errlen, "unknown error");
    }
    return -1;
}

EXPORT void las_lsfg_delete(int id) {
    try {
        if (g_perf) LSFG_3_1P::deleteContext(id); else LSFG_3_1::deleteContext(id);
    } catch (...) {}
}
