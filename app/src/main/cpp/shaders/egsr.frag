#version 450
// EGSR (Epinephro Game Super Resolution) = Epinephro's own spatial upscaler. Original work, no code from SGSR/FSR (it only reuses SGSR's texel-gather layout so the vertex
// shader and push-constant block are shared). Reference implementation and tuning: episr-lab/lab.py (up_epi2).
//   1. Gate: the range of three reads (two gathered green texels and the hardware bilinear read) tells if the pixel is on an edge.
//      A flat pixel keeps the one bilinear read and stops there (one gather, one bilinear).
//   2. Edge pixel: a separable Catmull-Rom over the 12 gathered texels. The 4+4 per-axis weights are computed once, each texel uses a product.
//      No edge direction, no per-texel contrast weights: far fewer ALU ops than SGSR Quality's 12 direction-dependent kernels.
//   4. Texture sharpening in the style of CeeJay.dk's LumaSharpen (bilinear minus the mean of four bilinear reads half an output pixel away on
//      the diagonals, scaled, clamped, luma-weighted), on every pixel but faded out on edges so it never doubles the edge boost.
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

    highp vec2 imgCoord = (uv * pc.ViewportInfo.zw) + vec2(-0.5, 0.5);
    highp vec2 imgCoordPixel = floor(imgCoord);
    highp vec2 coord = imgCoordPixel * pc.ViewportInfo.xy;
    vec2 pl = imgCoord - imgCoordPixel;
    float a = 0.0;
    if (q2w > 0.5) {   // native size: nothing to reconstruct, so the edge boost works as an unsharp mask against the four neighbouring texels
        highp vec2 tx = pc.ViewportInfo.xy;
        float gl = textureLod(ps0, uv - vec2(tx.x, 0.0), 0.0).y, gr = textureLod(ps0, uv + vec2(tx.x, 0.0), 0.0).y;
        float gu = textureLod(ps0, uv - vec2(0.0, tx.y), 0.0).y, gd = textureLod(ps0, uv + vec2(0.0, tx.y), 0.0).y;
        float hiN = max(max(max(gl, gr), max(gu, gd)), col.y), loN = min(min(min(gl, gr), min(gu, gd)), col.y);
        float rngN = hiN - loN;
        float aN = clamp((rngN - q0.z) / (2.0 * q0.z), 0.0, 1.0);
        if (aN > 0.0) {
            float detN = col.y - 0.25 * (gl + gr + gu + gd);
            float tgtN = col.y + 0.5 * q0.x * aN * detN / (1.0 + abs(detN) / (q0.y + 0.5 * rngN));
            tgtN = clamp(tgtN, loN - q0.w * rngN, hiN + q0.w * rngN);
            float dN = clamp(tgtN - col.y, -q1.x, q1.x);
            col = clamp(col + vec3(dN), 0.0, 1.0);
        }
        a = aN;
    } else {
    {
    vec4 left = textureGather(ps0, coord, 1);

    float hi = max(max(left.y, left.z), col.y);
    float lo = min(min(left.y, left.z), col.y);
    float edge = q0.z;
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
        float tgt = rec + q0.x * a * det / (1.0 + abs(det) / (q0.y + 0.5 * rng));   // the soft limit and the margin scale with the local contrast
        tgt = clamp(tgt, mn - q0.w * rng, mx + q0.w * rng);
        float delta = clamp(tgt - col.y, -q1.x, q1.x);
        col = clamp(col + vec3(delta), 0.0, 1.0);
    }
    }
    }
    if (q1.z > 0.0) {   // LumaSharpen-style texture sharpening, weaker the more the pixel is an edge
        highp vec2 o = pc.t2.xy;
        vec3 t0 = textureLod(ps0, uv + vec2(o.x, -o.y), 0.0).xyz, t1 = textureLod(ps0, uv + vec2(-o.x, o.y), 0.0).xyz;
        vec3 t2 = textureLod(ps0, uv - o, 0.0).xyz, t3 = textureLod(ps0, uv + o, 0.0).xyz;
        const vec3 LC = vec3(0.2126, 0.7152, 0.0722);
        float sl = dot(bil - 0.25 * (t0 + t1 + t2 + t3), LC) * q1.z;
        float k = 1.0;
        if (q2z > 0.0) {   // noise floor: no sharpening where the four reads barely differ (sky, water, gradients, compression noise)
            float l0 = dot(t0, LC), l1 = dot(t1, LC), l2 = dot(t2, LC), l3 = dot(t3, LC), lb = dot(bil, LC);
            float rg = max(max(max(l0, l1), max(l2, l3)), lb) - min(min(min(l0, l1), min(l2, l3)), lb);
            k = smoothstep(0.0, q2z, rg);
        }
        col = clamp(col + vec3(clamp(sl, -q1.w, q1.w) * k * (1.0 - a)), 0.0, 1.0);
    }
    out_Target0 = vec4(col, 1.0);
}
