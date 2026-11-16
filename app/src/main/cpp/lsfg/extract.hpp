#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace Extract {

    void setDllPath(const std::string& path);
    std::vector<std::string> shaderNames();

    ///
    /// Extract all known shaders.
    ///
    /// @throws std::runtime_error if shader extraction fails.
    ///
    void extractShaders();

    ///
    /// Get a shader by name.
    ///
    /// @param name The name of the shader to get.
    /// @return The shader bytecode.
    ///
    /// @throws std::runtime_error if the shader is not found.
    ///
    std::vector<uint8_t> getShader(const std::string& name);

    ///
    /// The 16-bit variant of a shader, if the DLL carries one: resource id + 49 (the FP32 ids are 255..302, the FP16 ones 304..351) holding
    /// SPIR-V. Empty when absent or not SPIR-V.
    ///
    std::vector<uint8_t> getShaderFp16(const std::string& name);

    /// One line about what the DLL's resources look like around the shader ids (for the log).
    std::string resourceSummary();

    ///
    /// Can `raw` (SPIR-V from the DLL, 16-bit) replace `translated` (the FP32 shader after translateShader)? It must use Float16 and expose the same
    /// descriptor set/binding layout. `why` says what failed.
    ///
    bool fp16Compatible(const std::vector<uint8_t>& raw, const std::vector<uint8_t>& translated, std::string& why);

}
