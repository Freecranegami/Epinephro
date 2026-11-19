#version 450
#extension GL_GOOGLE_include_directive : enable
// FSR-1P: FSR 1 in one pass. EASU (AMD FidelityFX FSR 1.0, MIT licence, Copyright (c) 2021 Advanced Micro Devices, Inc.)
// is run once per output pixel as usual. RCAS then needs the four neighbouring EASU results, which the two-pass chain reads
// back from a full-size image. Here they are estimated instead: a bilinear read of the input at the neighbour's position,
// shifted by what EASU added at this pixel (EASU centre minus bilinear centre). That keeps the edge detail and costs five
// bilinear reads instead of a full-size write plus five reads. The RCAS maths below is FsrRcasF (no denoise) unchanged.
layout(set = 0, binding = 0) uniform sampler2D tex;                   // bilinear, clamp to edge
layout(push_constant) uniform PC { uvec4 con0; uvec4 con1; uvec4 con2; uvec4 con3; uvec4 rc; } pc;
layout(location = 0) out vec4 outColor;

#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return textureGather(tex, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(tex, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(tex, p, 2); }
#include "ffx_fsr1.h"

void main() {
    AF3 e;
    FsrEasuF(e, AU2(gl_FragCoord.xy), pc.con0, pc.con1, pc.con2, pc.con3);
    AF2 sc = uintBitsToFloat(pc.con0.xy);                  // input texels per output pixel
    AF2 pp = AF2(gl_FragCoord.xy) * sc + uintBitsToFloat(pc.con0.zw) + AF2(0.5);   // input position in texels, bilinear centre
    AF2 it = uintBitsToFloat(pc.con1.xy);                  // 1 / input size
    AF3 c = texture(tex, pp * it).rgb;
    AF3 corr = e - c;
    AF3 b = texture(tex, (pp + AF2(0.0, -sc.y)) * it).rgb + corr;
    AF3 d = texture(tex, (pp + AF2(-sc.x, 0.0)) * it).rgb + corr;
    AF3 f = texture(tex, (pp + AF2(sc.x, 0.0)) * it).rgb + corr;
    AF3 h = texture(tex, (pp + AF2(0.0, sc.y)) * it).rgb + corr;
    AF3 mn4 = min(min(b, d), min(f, h));
    AF3 mx4 = max(max(b, d), max(f, h));
    AF3 hitMin = min(mn4, e) / (4.0 * max(mx4, vec3(1e-4)));
    AF3 hitMax = (1.0 - max(mx4, e)) / (4.0 * min(mn4, vec3(0.9999)) - 4.0);
    AF3 l3 = max(-hitMin, hitMax);
    AF1 lobe = max(-FSR_RCAS_LIMIT, min(max(l3.r, max(l3.g, l3.b)), 0.0)) * uintBitsToFloat(pc.rc.x);
    AF3 o = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
    outColor = vec4(clamp(o, 0.0, 1.0), 1.0);
}
