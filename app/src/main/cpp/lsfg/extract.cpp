#include <cstring>
#include "extract.hpp"

#include <pe-parse/parse.h>

#include <cstdlib>
#include <filesystem>
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

using namespace Extract;

const std::unordered_map<std::string, uint32_t> nameIdxTable = {{
    { "mipmaps", 255 },
    { "alpha[0]", 267 },
    { "alpha[1]", 268 },
    { "alpha[2]", 269 },
    { "alpha[3]", 270 },
    { "beta[0]", 275 },
    { "beta[1]", 276 },
    { "beta[2]", 277 },
    { "beta[3]", 278 },
    { "beta[4]", 279 },
    { "gamma[0]", 257 },
    { "gamma[1]", 259 },
    { "gamma[2]", 260 },
    { "gamma[3]", 261 },
    { "gamma[4]", 262 },
    { "delta[0]", 257 },
    { "delta[1]", 263 },
    { "delta[2]", 264 },
    { "delta[3]", 265 },
    { "delta[4]", 266 },
    { "delta[5]", 258 },
    { "delta[6]", 271 },
    { "delta[7]", 272 },
    { "delta[8]", 273 },
    { "delta[9]", 274 },
    { "generate", 256 },
    { "p_mipmaps", 255 },
    { "p_alpha[0]", 290 },
    { "p_alpha[1]", 291 },
    { "p_alpha[2]", 292 },
    { "p_alpha[3]", 293 },
    { "p_beta[0]", 298 },
    { "p_beta[1]", 299 },
    { "p_beta[2]", 300 },
    { "p_beta[3]", 301 },
    { "p_beta[4]", 302 },
    { "p_gamma[0]", 280 },
    { "p_gamma[1]", 282 },
    { "p_gamma[2]", 283 },
    { "p_gamma[3]", 284 },
    { "p_gamma[4]", 285 },
    { "p_delta[0]", 280 },
    { "p_delta[1]", 286 },
    { "p_delta[2]", 287 },
    { "p_delta[3]", 288 },
    { "p_delta[4]", 289 },
    { "p_delta[5]", 281 },
    { "p_delta[6]", 294 },
    { "p_delta[7]", 295 },
    { "p_delta[8]", 296 },
    { "p_delta[9]", 297 },
    { "p_generate", 256 },
}};

namespace {
    auto& shaders() {
        static std::unordered_map<uint32_t, std::vector<uint8_t>> shaderData;
        return shaderData;
    }

    int on_resource(void*, const peparse::resource& res) {
        if (res.type != peparse::RT_RCDATA || res.buf == nullptr || res.buf->bufLen <= 0)
            return 0;
        std::vector<uint8_t> resource_data(res.buf->bufLen);
        std::copy_n(res.buf->buf, res.buf->bufLen, resource_data.data());
        shaders()[res.name] = resource_data;
        return 0;
    }

    std::string g_dllPath;

    std::string getDllPath() {
        if (!g_dllPath.empty()) return g_dllPath;
        const char* p = getenv("LAS_LSFG_DLL");
        return (p && *p) ? std::string(p) : std::string("Lossless.dll");
    }
}

void Extract::setDllPath(const std::string& p) {
    g_dllPath = p;
    shaders().clear();
}

std::vector<std::string> Extract::shaderNames() {
    std::vector<std::string> v;
    for (const auto& kv : nameIdxTable) v.push_back(kv.first);
    std::sort(v.begin(), v.end());
    return v;
}

void Extract::extractShaders() {
    if (!shaders().empty())
        return;

    // parse the dll
    peparse::parsed_pe* dll = peparse::ParsePEFromFile(getDllPath().c_str());
    if (!dll)
        throw std::runtime_error("Unable to read Lossless.dll, is it installed?");
    peparse::IterRsrc(dll, on_resource, nullptr);
    peparse::DestructParsedPE(dll);

    // ensure all shaders are present
    for (const auto& [name, idx] : nameIdxTable)
        if (shaders().find(idx) == shaders().end())
            throw std::runtime_error("Shader not found: " + name + ".\n- Is Lossless Scaling up to date?");
}

std::vector<uint8_t> Extract::getShader(const std::string& name) {
    if (shaders().empty())
        throw std::runtime_error("Shaders are not loaded.");

    auto hit = nameIdxTable.find(name);
    if (hit == nameIdxTable.end())
        throw std::runtime_error("Shader hash not found: " + name);

    auto sit = shaders().find(hit->second);
    if (sit == shaders().end())
        throw std::runtime_error("Shader not found: " + name);

    return sit->second;
}

std::vector<uint8_t> Extract::getShaderFp16(const std::string& name) {
    auto hit = nameIdxTable.find(name);
    if (hit == nameIdxTable.end()) return {};
    auto sit = shaders().find(hit->second + 49);
    if (sit == shaders().end() || sit->second.size() < 20) return {};
    uint32_t magic; std::memcpy(&magic, sit->second.data(), 4);
    if (magic != 0x07230203u) return {};
    return sit->second;
}

std::string Extract::resourceSummary() {
    size_t n = 0, spv = 0, lo = 0, hi = 0; bool any = false;
    for (const auto& [id, data] : shaders()) {
        if (id < 250 || id > 360) continue;
        n++; if (!any || id < lo) lo = id; if (!any || id > hi) hi = id; any = true;
        uint32_t m = 0; if (data.size() >= 4) std::memcpy(&m, data.data(), 4);
        if (m == 0x07230203u) spv++;
    }
    return std::to_string(n) + " resources with ids " + std::to_string(lo) + ".." + std::to_string(hi) + " (ids 250..360), " + std::to_string(spv) + " of them SPIR-V";
}

namespace {
    // set/binding pairs and Float16 capability of a SPIR-V module
    struct SpvInfo { std::vector<std::pair<uint32_t, uint32_t>> sb; bool f16 = false; bool ok = false; };
    SpvInfo scan(const std::vector<uint8_t>& b) {
        SpvInfo r; if (b.size() < 20 || b.size() % 4) return r;
        std::vector<uint32_t> w(b.size() / 4); std::memcpy(w.data(), b.data(), b.size());
        if (w[0] != 0x07230203u) return r;
        std::unordered_map<uint32_t, uint32_t> set, bind;
        for (size_t i = 5; i < w.size();) {
            uint32_t op = w[i] & 0xFFFF, len = w[i] >> 16;
            if (len == 0 || i + len > w.size()) return r;
            if (op == 17 && len >= 2 && w[i + 1] == 9) r.f16 = true;                          // OpCapability Float16
            if (op == 71 && len >= 4) {                                                      // OpDecorate
                if (w[i + 2] == 33) bind[w[i + 1]] = w[i + 3];                               // Binding
                if (w[i + 2] == 34) set[w[i + 1]] = w[i + 3];                                // DescriptorSet
            }
            if (op == 54) break;                                                             // OpFunction: decorations are done
            i += len;
        }
        for (auto& [id, bd] : bind) r.sb.push_back({ set.count(id) ? set[id] : 0u, bd });
        std::sort(r.sb.begin(), r.sb.end()); r.ok = true; return r;
    }
}

bool Extract::fp16Compatible(const std::vector<uint8_t>& raw, const std::vector<uint8_t>& translated, std::string& why) {
    SpvInfo a = scan(raw), b = scan(translated);
    if (!a.ok) { why = "not valid SPIR-V"; return false; }
    if (!a.f16) { why = "does not use Float16"; return false; }
    if (!b.ok) { why = "translated shader not readable"; return false; }
    if (a.sb != b.sb) { why = "descriptor layout differs (" + std::to_string(a.sb.size()) + " vs " + std::to_string(b.sb.size()) + " bindings)"; return false; }
    return true;
}
