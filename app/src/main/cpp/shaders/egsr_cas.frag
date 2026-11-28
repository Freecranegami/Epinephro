#version 450
// EGSR (Epinephro Game Super Resolution) = Epinephro's own spatial upscaler. Original work, no code from SGSR/FSR (it only reuses SGSR's texel-gather layout so the vertex
// shader and push-constant block are shared). Reference implementation and tuning: episr-lab/lab.py (up_epi2).
//   1. Gate: the range of three reads (two gathered texels and the hardware bilinear read) tells if the pixel is on an edge. It is taken on the colour
//      channel that changes most (red text, blue icons), so coloured UI is not missed. A flat grey pixel keeps the one bilinear read and one gather.
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

precision mediump float;   // all colour and weight maths in half precision; positions, uv and push-constant offsets are explicit highp

// Catmull-Rom, r >= 0
float cr(float r) {
    float r2 = r * r;
    return r < 1.0 ? (1.5 * r - 2.5) * r2 + 1.0 : ((-0.5 * r + 2.5) * r - 4.0) * r + 2.0;
}

void main() {
    // one conversion of the push constants into half precision; only positions and uv offsets stay highp
    mediump vec4 q0 = pc.t0, q1 = pc.t1;
    mediump float q2z = pc.t2.z, q2w = pc.t2.w;
    vec3 col = textureLod(ps0, uv, 0.0).xyz;
    vec3 bil = col;
    if (q1.y > 0.5 && uv.x < 0.5) { out_Target0 = vec4(col, 1.0); return; }

    float a = 0.0;
    float rngG = 0.0;   // the pixel's edge range (the gate's own reads)
    // Colour edges: the gate and the reconstruction follow the colour channel that changes most at this pixel (red text, blue icons), not always green.
    // The boost goes to that channel in full and to the others in proportion to how much they change, so grey stays as before and a coloured outline keeps its hue.
    if (q2w > 0.5) {   // native size: nothing to reconstruct, so the edge boost works as an unsharp mask against the four neighbouring texels
        highp vec2 tx = pc.ViewportInfo.xy;
        vec3 nl = textureLod(ps0, uv - vec2(tx.x, 0.0), 0.0).xyz, nr = textureLod(ps0, uv + vec2(tx.x, 0.0), 0.0).xyz;
        vec3 nu = textureLod(ps0, uv - vec2(0.0, tx.y), 0.0).xyz, nd = textureLod(ps0, uv + vec2(0.0, tx.y), 0.0).xyz;
        vec3 hiN = max(max(max(nl, nr), max(nu, nd)), col), loN = min(min(min(nl, nr), min(nu, nd)), col);
        vec3 rcN = hiN - loN;
        float rngN = max(max(rcN.x, rcN.y), rcN.z);
        float aN = clamp((rngN - q0.z) / (2.0 * q0.z), 0.0, 1.0);
        if (aN > 0.0) {
            int dc = (rcN.y >= rcN.x && rcN.y >= rcN.z) ? 1 : (rcN.x >= rcN.z ? 0 : 2);
            float c = col[dc];
            float detN = c - 0.25 * (nl[dc] + nr[dc] + nu[dc] + nd[dc]);
            float tgtN = c + 0.5 * q0.x * aN * detN / (1.0 + abs(detN) / (q0.y + 0.5 * rngN));
            tgtN = clamp(tgtN, loN[dc] - q0.w * rngN, hiN[dc] + q0.w * rngN);
            float dN = clamp(tgtN - c, -q1.x, q1.x);
            col = clamp(col + vec3(dN) * clamp(rcN / max(rngN, 0.0001), 0.0, 1.0), 0.0, 1.0);
        }
        a = aN; rngG = rngN;
    } else {
        highp vec2 imgCoord = (uv * pc.ViewportInfo.zw) + vec2(-0.5, 0.5);
        highp vec2 imgCoordPixel = floor(imgCoord);
        highp vec2 coord = imgCoordPixel * pc.ViewportInfo.xy;
        vec2 pl = imgCoord - imgCoordPixel;
        vec4 lG = textureGather(ps0, coord, 1), lR = lG, lB = lG;
        float edge = q0.z;
        float rgG = max(max(lG.y, lG.z), col.y) - min(min(lG.y, lG.z), col.y);
        // red and blue are only read where the pixel is coloured or green already sees an edge: grey flat areas keep the one gather
        if (rgG >= edge || max(abs(col.x - col.y), abs(col.z - col.y)) > 0.04) {
            lR = textureGather(ps0, coord, 0); lB = textureGather(ps0, coord, 2);
        }
        vec3 hi3 = max(max(vec3(lR.y, lG.y, lB.y), vec3(lR.z, lG.z, lB.z)), col);
        vec3 lo3 = min(min(vec3(lR.y, lG.y, lB.y), vec3(lR.z, lG.z, lB.z)), col);
        vec3 rc = hi3 - lo3;
        float rd = max(max(rc.x, rc.y), rc.z);
        a = clamp((rd - edge) / (2.0 * edge), 0.0, 1.0);
        rngG = rd;
        if (a > 0.0) {
            int dc = (rc.y >= rc.x && rc.y >= rc.z) ? 1 : (rc.x >= rc.z ? 0 : 2);
            vec4 left = dc == 1 ? lG : (dc == 0 ? lR : lB);
            vec4 right, ud;
            coord.x += pc.ViewportInfo.x;
            highp vec2 cRt = coord + vec2(pc.ViewportInfo.x, 0.0), cu = coord + vec2(0.0, -pc.ViewportInfo.y), cd = coord + vec2(0.0, pc.ViewportInfo.y);
            if (dc == 1) {
                right = textureGather(ps0, cRt, 1);
                ud.xy = textureGather(ps0, cu, 1).wz;   // texels (0,-2), (1,-2)
                ud.zw = textureGather(ps0, cd, 1).yx;   // texels (1, 1), (0, 1)
            } else if (dc == 0) {
                right = textureGather(ps0, cRt, 0);
                ud.xy = textureGather(ps0, cu, 0).wz;
                ud.zw = textureGather(ps0, cd, 0).yx;
            } else {
                right = textureGather(ps0, cRt, 2);
                ud.xy = textureGather(ps0, cu, 2).wz;
                ud.zw = textureGather(ps0, cd, 2).yx;
            }
            float c = col[dc];

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

            float det = rec - c;
            float mx = max(max(left.y, left.z), max(right.x, right.w));
            float mn = min(min(left.y, left.z), min(right.x, right.w));
            float rng = mx - mn;
            float tgt = rec + q0.x * a * det / (1.0 + abs(det) / (q0.y + 0.5 * rng));   // the soft limit and the margin scale with the local contrast
            tgt = clamp(tgt, mn - q0.w * rng, mx + q0.w * rng);
            float delta = clamp(tgt - c, -q1.x, q1.x);
            col = clamp(col + vec3(delta) * clamp(rc / max(rd, 0.0001), 0.0, 1.0), 0.0, 1.0);
        }
    }
    if (q1.z > 0.0 && a < 1.0 && rngG > q2z) {   // CAS: 4 bilinear reads one output pixel away; flat pixels (range from the gate's own reads below the floor) skip them
        highp vec2 o = pc.t2.xy;
        vec3 n = textureLod(ps0, uv - vec2(0.0, o.y), 0.0).xyz, w = textureLod(ps0, uv - vec2(o.x, 0.0), 0.0).xyz;
        vec3 e = textureLod(ps0, uv + vec2(o.x, 0.0), 0.0).xyz, s = textureLod(ps0, uv + vec2(0.0, o.y), 0.0).xyz;
        vec3 mn = min(min(min(n, w), min(e, s)), bil), mx = max(max(max(n, w), max(e, s)), bil);
        // AMD FidelityFX CAS: lobe from the headroom to 0 and 1 over the peak, strength 0..1 (MIT, (c) 2019 Advanced Micro Devices, Inc.)
        vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
        float peak = -1.0 / (8.0 - 3.0 * q1.z);
        vec3 wgt = amp * peak;
        vec3 sharp = ((n + w + e + s) * wgt + bil) / (4.0 * wgt + 1.0);
        col = clamp(col + (sharp - bil) * (1.0 - a), 0.0, 1.0);
    }
    out_Target0 = vec4(col, 1.0);
}
