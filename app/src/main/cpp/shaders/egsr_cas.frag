#version 450
// EGSR (Epinephro Game Super Resolution) = Epinephro's own spatial upscaler. Original work, no code from SGSR/FSR (it only reuses SGSR's texel-gather layout so the vertex
// shader and push-constant block are shared). Reference implementation and tuning: episr-lab/lab.py (up_epi2).
//   1. Gate: the range of three reads (two gathered green texels and the hardware bilinear read) tells if the pixel is on an edge.
//      A flat pixel keeps the one bilinear read and stops there (one gather, one bilinear).
//   2. Edge pixel: a separable Catmull-Rom over the 12 gathered texels. The 4+4 per-axis weights are computed once, each texel uses a product.
//      No edge direction, no per-texel contrast weights: far fewer ALU ops than SGSR Quality's 12 direction-dependent kernels.
//   4. EGSR-CAS variant: the LumaSharpen texture sharpening is replaced by AMD CAS (4 reads at +-1 output pixel, adaptive lobe), skipped on flat pixels, faded on edges.
//   3. The cubic's difference from the bilinear read is boosted with a soft limit d / (1 + |d| / lim), scaled by the gate, kept within the
//      four centre texels' range plus a margin (both limits scale with the local range), and added equally to R, G and B.
layout(set = 0, binding = 0) uniform sampler2D ps0;
layout(push_constant) uniform PC {
    highp vec4 ViewportInfo;   // 1/w, 1/h, w, h of the input
    highp vec4 t0;             // boost, limit (+ half the local range), edge threshold, margin (share of the local range)
    highp vec4 t1;             // delta limit, compare (left half bilinear), texture sharpness, texture clamp
    highp vec4 t2;             // texture sharpness tap offset in uv (half an output pixel times the offset setting), texture noise floor, native (1 = output size equals input size)
} pc;
layout(location = 0) in highp vec2 uv;
layout(location = 0) out vec4 out_Target0;

precision mediump float;

// Catmull-Rom, r >= 0
float cr(float r) {
    float r2 = r * r;
    return r < 1.0 ? (1.5 * r - 2.5) * r2 + 1.0 : ((-0.5 * r + 2.5) * r - 4.0) * r + 2.0;
}

void main() {
    vec3 col = textureLod(ps0, uv, 0.0).xyz;
    vec3 bil = col;
    if (pc.t1.y > 0.5 && uv.x < 0.5) { out_Target0 = vec4(col, 1.0); return; }

    highp vec2 imgCoord = (uv * pc.ViewportInfo.zw) + vec2(-0.5, 0.5);
    highp vec2 imgCoordPixel = floor(imgCoord);
    highp vec2 coord = imgCoordPixel * pc.ViewportInfo.xy;
    vec2 pl = imgCoord - imgCoordPixel;
    float a = 0.0;
    float rngG = 0.0;
    if (pc.t2.w > 0.5) {   // native size: nothing to reconstruct, so the edge boost works as an unsharp mask against the four neighbouring texels
        vec2 tx = pc.ViewportInfo.xy;
        float gl = textureLod(ps0, uv - vec2(tx.x, 0.0), 0.0).y, gr = textureLod(ps0, uv + vec2(tx.x, 0.0), 0.0).y;
        float gu = textureLod(ps0, uv - vec2(0.0, tx.y), 0.0).y, gd = textureLod(ps0, uv + vec2(0.0, tx.y), 0.0).y;
        float hiN = max(max(max(gl, gr), max(gu, gd)), col.y), loN = min(min(min(gl, gr), min(gu, gd)), col.y);
        float rngN = hiN - loN;
        float aN = clamp((rngN - pc.t0.z) / (2.0 * pc.t0.z), 0.0, 1.0);
        if (aN > 0.0) {
            float detN = col.y - 0.25 * (gl + gr + gu + gd);
            float tgtN = col.y + 0.5 * pc.t0.x * aN * detN / (1.0 + abs(detN) / (pc.t0.y + 0.5 * rngN));
            tgtN = clamp(tgtN, loN - pc.t0.w * rngN, hiN + pc.t0.w * rngN);
            float dN = clamp(tgtN - col.y, -pc.t1.x, pc.t1.x);
            col = clamp(col + vec3(dN), 0.0, 1.0);
        }
        a = aN; rngG = rngN;
    } else {
    {
    vec4 left = textureGather(ps0, coord, 1);

    highp float hi = max(max(left.y, left.z), col.y);
    highp float lo = min(min(left.y, left.z), col.y);
    highp float edge = pc.t0.z;
    rngG = hi - lo;
    a = clamp((hi - lo - edge) / (2.0 * edge), 0.0, 1.0);
    if (a > 0.0) {
        coord.x += pc.ViewportInfo.x;
        vec4 right = textureGather(ps0, coord + vec2(pc.ViewportInfo.x, 0.0), 1);
        vec4 ud;
        ud.xy = textureGather(ps0, coord + vec2(0.0, -pc.ViewportInfo.y), 1).wz;   // texels (0,-2), (1,-2)
        ud.zw = textureGather(ps0, coord + vec2(0.0, pc.ViewportInfo.y), 1).yx;    // texels (1, 1), (0, 1)

        // per-axis weights; columns -1, 0, 1, 2 and rows -2, -1, 0, 1 around the 2x2 centre
        float wx0 = cr(pl.x + 1.0), wx1 = cr(pl.x), wx2 = cr(1.0 - pl.x), wx3 = cr(2.0 - pl.x);
        float wy0 = cr(pl.y + 1.0), wy1 = cr(pl.y), wy2 = cr(1.0 - pl.y), wy3 = cr(2.0 - pl.y);
        // texels: left.x(-1,0) left.y(0,0) left.z(0,-1) left.w(-1,-1) right.x(1,0) right.y(2,0) right.z(2,-1) right.w(1,-1)
        //         ud.x(0,-2) ud.y(1,-2) ud.z(1,1) ud.w(0,1)
        float sw = wx1 * wy0 + wx2 * wy0                                 // row -2: columns 0, 1
                 + wx0 * wy1 + wx1 * wy1 + wx2 * wy1 + wx3 * wy1         // row -1: columns -1..2
                 + wx0 * wy2 + wx1 * wy2 + wx2 * wy2 + wx3 * wy2         // row  0: columns -1..2
                 + wx1 * wy3 + wx2 * wy3;                                // row  1: columns 0, 1
        float sy = wy0 * (wx1 * ud.x + wx2 * ud.y)
                 + wy1 * (wx0 * left.w + wx1 * left.z + wx2 * right.w + wx3 * right.z)
                 + wy2 * (wx0 * left.x + wx1 * left.y + wx2 * right.x + wx3 * right.y)
                 + wy3 * (wx1 * ud.w + wx2 * ud.z);
        float rec = sy / sw;

        float det = rec - col.y;
        float mx = max(max(left.y, left.z), max(right.x, right.w));
        float mn = min(min(left.y, left.z), min(right.x, right.w));
        float rng = mx - mn;
        float tgt = rec + pc.t0.x * a * det / (1.0 + abs(det) / (pc.t0.y + 0.5 * rng));   // the soft limit and the margin scale with the local contrast
        tgt = clamp(tgt, mn - pc.t0.w * rng, mx + pc.t0.w * rng);
        float delta = clamp(tgt - col.y, -pc.t1.x, pc.t1.x);
        col = clamp(col + vec3(delta), 0.0, 1.0);
    }
    }
    }
    if (pc.t1.z > 0.0 && rngG > pc.t2.z) {   // CAS: 4 bilinear reads one output pixel away; flat pixels (range from the gate's own reads below the floor) skip them
        vec2 o = pc.t2.xy;
        vec3 n = textureLod(ps0, uv - vec2(0.0, o.y), 0.0).xyz, w = textureLod(ps0, uv - vec2(o.x, 0.0), 0.0).xyz;
        vec3 e = textureLod(ps0, uv + vec2(o.x, 0.0), 0.0).xyz, s = textureLod(ps0, uv + vec2(0.0, o.y), 0.0).xyz;
        vec3 mn = min(min(min(n, w), min(e, s)), bil), mx = max(max(max(n, w), max(e, s)), bil);
        // AMD FidelityFX CAS: lobe from the headroom to 0 and 1 over the peak, strength 0..1 (MIT, (c) 2019 Advanced Micro Devices, Inc.)
        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
        float peak = -1.0 / (8.0 - 3.0 * pc.t1.z);
        vec3 wgt = amp * peak;
        vec3 sharp = ((n + w + e + s) * wgt + bil) / (4.0 * wgt + 1.0);
        col = clamp(col + (sharp - bil) * (1.0 - a), 0.0, 1.0);
    }
    out_Target0 = vec4(col, 1.0);
}
