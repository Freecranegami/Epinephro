#version 450
// FSR 1, pass 1: EASU in half precision (RelaxedPrecision), for Adreno and other GPUs that run 16-bit maths at about twice the rate.
// The algorithm and constants are AMD FidelityFX FSR 1.0 (github.com/GPUOpen-Effects/FidelityFX-FSR, MIT licence, Copyright (c) 2021
// Advanced Micro Devices, Inc.), the same as fsr_easu.frag. All colour maths is 'mediump'; positions and texture coordinates stay
// 'highp' (16 bits cannot address a 2000-pixel image). AMD's bit-trick reciprocal approximations are replaced by plain ones because the
// bit tricks need real 32-bit floats; the clamps keep the results inside the 16-bit range.
layout(set = 0, binding = 0) uniform sampler2D tex;                   // bilinear, clamp to edge
layout(push_constant) uniform PC { uvec4 con0; uvec4 con1; uvec4 con2; uvec4 con3; } pc;
layout(location = 0) out vec4 outColor;
precision mediump float;

void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = off.x * dir.x + off.y * dir.y;
    v.y = off.x * -dir.y + off.y * dir.x;
    v *= len;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w; aW += w;
}

void set(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV,
         float lA, float lB, float lC, float lD, float lE) {
    float w = 0.0;
    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
    if (biT) w = pp.x * (1.0 - pp.y);
    if (biU) w = (1.0 - pp.x) * pp.y;
    if (biV) w = pp.x * pp.y;
    float dc = lD - lC, cb = lC - lB;
    float lenX = 1.0 / max(max(abs(dc), abs(cb)), 6.1e-5);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC, ca = lC - lA;
    float lenY = 1.0 / max(max(abs(ec), abs(ca)), 6.1e-5);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void main() {
    highp vec2 ip = vec2(uvec2(gl_FragCoord.xy));
    highp vec2 pph = ip * uintBitsToFloat(pc.con0.xy) + uintBitsToFloat(pc.con0.zw);
    highp vec2 fp = floor(pph);
    vec2 pp = pph - fp;                                   // 0..1: fine in half precision
    highp vec2 p0 = fp * uintBitsToFloat(pc.con1.xy) + uintBitsToFloat(pc.con1.zw);
    highp vec2 p1 = p0 + uintBitsToFloat(pc.con2.xy);
    highp vec2 p2 = p0 + uintBitsToFloat(pc.con2.zw);
    highp vec2 p3 = p0 + uintBitsToFloat(pc.con3.xy);
    vec4 bczzR = textureGather(tex, p0, 0), bczzG = textureGather(tex, p0, 1), bczzB = textureGather(tex, p0, 2);
    vec4 ijfeR = textureGather(tex, p1, 0), ijfeG = textureGather(tex, p1, 1), ijfeB = textureGather(tex, p1, 2);
    vec4 klhgR = textureGather(tex, p2, 0), klhgG = textureGather(tex, p2, 1), klhgB = textureGather(tex, p2, 2);
    vec4 zzonR = textureGather(tex, p3, 0), zzonG = textureGather(tex, p3, 1), zzonB = textureGather(tex, p3, 2);
    vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);
    vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);
    vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);
    vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);
    float bL = bczzL.x, cL = bczzL.y, iL = ijfeL.x, jL = ijfeL.y, fL = ijfeL.z, eL = ijfeL.w;
    float kL = klhgL.x, lL = klhgL.y, hL = klhgL.z, gL = klhgL.w, oL = zzonL.z, nL = zzonL.w;
    vec2 dir = vec2(0.0);
    float len = 0.0;
    set(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);
    set(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);
    set(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);
    set(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < 1.0 / 16384.0;
    dirR = inversesqrt(max(dirR, 1.0 / 16384.0));
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= dirR;
    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;
    vec3 min4 = min(min(vec3(ijfeR.z, ijfeG.z, ijfeB.z), min(vec3(klhgR.w, klhgG.w, klhgB.w), vec3(ijfeR.y, ijfeG.y, ijfeB.y))),
                    vec3(klhgR.x, klhgG.x, klhgB.x));
    vec3 max4 = max(max(vec3(ijfeR.z, ijfeG.z, ijfeB.z), max(vec3(klhgR.w, klhgG.w, klhgB.w), vec3(ijfeR.y, ijfeG.y, ijfeB.y))),
                    vec3(klhgR.x, klhgG.x, klhgB.x));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    tap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.x, bczzG.x, bczzB.x));
    tap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, vec3(bczzR.y, bczzG.y, bczzB.y));
    tap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.x, ijfeG.x, ijfeB.x));
    tap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, vec3(ijfeR.y, ijfeG.y, ijfeB.y));
    tap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.z, ijfeG.z, ijfeB.z));
    tap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, vec3(ijfeR.w, ijfeG.w, ijfeB.w));
    tap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, vec3(klhgR.x, klhgG.x, klhgB.x));
    tap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, vec3(klhgR.y, klhgG.y, klhgB.y));
    tap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, vec3(klhgR.z, klhgG.z, klhgB.z));
    tap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, vec3(klhgR.w, klhgG.w, klhgB.w));
    tap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, vec3(zzonR.z, zzonG.z, zzonB.z));
    tap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, vec3(zzonR.w, zzonG.w, zzonB.w));
    vec3 pix = min(max4, max(min4, aC * (1.0 / aW)));
    outColor = vec4(pix, 1.0);
}
