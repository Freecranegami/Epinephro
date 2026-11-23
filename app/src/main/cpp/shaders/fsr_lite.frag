#version 450
// FSR Lite: FSR 1 (EASU then RCAS) in ONE pass, with the same picture as the two-pass chain.
// EASU and RCAS are AMD FidelityFX FSR 1.0 (github.com/GPUOpen-Effects/FidelityFX-FSR, MIT licence, Copyright (c) 2021 Advanced Micro Devices, Inc.).
// RCAS needs the EASU result at the pixel and its four neighbours. The two-pass chain writes EASU to a full-size image and reads it back;
// here the neighbours are the real EASU result too, not an estimate: every pixel of a 2x2 quad exchanges its value with its horizontal and
// vertical quad partner (dFdxFine / dFdyFine, free), and only the two neighbours outside the quad are computed, so three EASU evaluations
// per pixel instead of five. Everything is half precision ('mediump', as fsr_easu_h.frag) except positions and the RCAS limiter divisions.
// Border pixels use the pixel itself in place of a neighbour outside the picture (clamp to edge, like the read-back).
#extension GL_ARB_derivative_control : enable
layout(set = 0, binding = 0) uniform sampler2D tex;                   // bilinear, clamp to edge
layout(push_constant) uniform PC { uvec4 con0; uvec4 con1; uvec4 con2; uvec4 con3; uvec4 rc; } pc;   // con3.zw: rect origin; rc.x: RCAS constant; rc.yz: rect last pixel
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

vec3 easu(highp vec2 ip) {
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
    return min(max4, max(min4, aC * (1.0 / aW)));
}

void main() {
    highp vec2 ip = vec2(uvec2(gl_FragCoord.xy));
    highp vec2 lo = vec2(uintBitsToFloat(pc.con3.z), uintBitsToFloat(pc.con3.w));
    highp vec2 hi = vec2(uintBitsToFloat(pc.rc.y), uintBitsToFloat(pc.rc.z));
    vec3 e = easu(ip);
    vec2 s = mix(vec2(1.0), vec2(-1.0), vec2(uvec2(gl_FragCoord.xy) & 1u));      // direction of this pixel's quad partner on each axis
    vec3 px = e + s.x * dFdxFine(e), py = e + s.y * dFdyFine(e);                 // the quad partners' EASU results
    highp vec2 ox = ip + vec2(s.x, 0.0), oy = ip + vec2(0.0, s.y);               // partner positions (could be outside the picture)
    if (ox.x < lo.x || ox.x > hi.x) px = e;
    if (oy.y < lo.y || oy.y > hi.y) py = e;
    vec3 qx = easu(clamp(ip - vec2(s.x, 0.0), lo, hi));                          // the neighbours on the other side, outside the quad
    vec3 qy = easu(clamp(ip - vec2(0.0, s.y), lo, hi));
    vec3 d = s.x > 0.0 ? qx : px, f = s.x > 0.0 ? px : qx;
    vec3 b = s.y > 0.0 ? qy : py, h = s.y > 0.0 ? py : qy;
    // RCAS (FsrRcasF without denoise): limiter divisions in highp so a white neighbourhood cannot overflow half precision
    highp vec3 mn4 = min(min(b, d), min(f, h)), mx4 = max(max(b, d), max(f, h));
    highp vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-6));
    highp vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-6));
    highp vec3 l3 = max(-hitMin, hitMax);
    highp float lobe = max(-(0.25 - 1.0 / 16.0), min(max(l3.r, max(l3.g, l3.b)), 0.0)) * uintBitsToFloat(pc.rc.x);
    outColor = vec4(clamp((lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0), 0.0, 1.0), 1.0);
}
