#version 450
// EpiSR Lite = Epinephro's own cheap spatial upscaler, one pass, no branches, five bilinear fetches per pixel.
// A Keys cubic (parameter a: -0.5 is Catmull-Rom, -1.0 is a Lanczos-like kernel with deeper negative lobes), done the way TAA resolves do it:
// each axis' four weights are folded into three bilinear fetches (the two inner taps share one), and the four corner taps are dropped, which
// leaves centre + left + right + up + down. Sharpening comes from the same five reads: centre minus the mean of the four outer taps, scaled
// like AMD's CAS (less where the local contrast is already high), and optionally kept within the range of the five reads (the ring margin).
// Original work; Keys, "Cubic convolution interpolation for digital image processing" (1981), and the five-tap fold from TAA practice.
layout(set = 0, binding = 0) uniform sampler2D ps0;
layout(push_constant) uniform PC {
    highp vec4 ViewportInfo;   // 1/w, 1/h, w, h of the input
    highp vec4 t0;             // kernel a, sharpness, ring margin (>= 1 = off), compare (1 = left half is plain bilinear)
} pc;
layout(location = 0) in highp vec2 uv;
layout(location = 0) out vec4 out_Target0;
precision mediump float;

highp vec3 wts(highp float f, highp float a) {          // the cubic's four weights for an offset f, folded to (w0, w1 + w2, w3)
    highp float d0 = 1.0 + f, d1 = f, d2 = 1.0 - f, d3 = 2.0 - f;
    highp float w0 = ((a * d0 - 5.0 * a) * d0 + 8.0 * a) * d0 - 4.0 * a;
    highp float w1 = ((a + 2.0) * d1 - (a + 3.0)) * d1 * d1 + 1.0;
    highp float w2 = ((a + 2.0) * d2 - (a + 3.0)) * d2 * d2 + 1.0;
    highp float w3 = ((a * d3 - 5.0 * a) * d3 + 8.0 * a) * d3 - 4.0 * a;
    return vec3(w0, w1 + w2, w3);
}

void main() {
    highp vec2 texel = pc.ViewportInfo.xy;
    highp vec2 sp = uv * pc.ViewportInfo.zw;
    highp vec2 i0 = floor(sp - 0.5);
    highp vec2 f = sp - 0.5 - i0;
    highp float a = pc.t0.x;
    highp vec3 wx = wts(f.x, a), wy = wts(f.y, a);
    // the middle tap sits between the two inner texels, where the bilinear weights give w2 / (w1 + w2)
    highp vec2 d2 = 1.0 - f;
    highp vec2 w2 = ((a + 2.0) * d2 - (a + 3.0)) * d2 * d2 + 1.0;
    highp vec2 mid = i0 + w2 / vec2(wx.y, wy.y);
    highp vec2 pL = (i0 - 0.5) * texel, pR = (i0 + 2.5) * texel;
    highp vec2 pM = (mid + 0.5) * texel;
    if (pc.t0.w > 0.5 && uv.x < 0.5) { out_Target0 = vec4(textureLod(ps0, uv, 0.0).rgb, 1.0); return; }
    vec3 c = textureLod(ps0, pM, 0.0).rgb;
    vec3 l = textureLod(ps0, vec2(pL.x, pM.y), 0.0).rgb;
    vec3 r = textureLod(ps0, vec2(pR.x, pM.y), 0.0).rgb;
    vec3 u = textureLod(ps0, vec2(pM.x, pL.y), 0.0).rgb;
    vec3 d = textureLod(ps0, vec2(pM.x, pR.y), 0.0).rgb;
    float wc = wx.y * wy.y, wl = wx.x * wy.y, wr = wx.z * wy.y, wu = wx.y * wy.x, wd = wx.y * wy.z;
    vec3 o = (c * wc + l * wl + r * wr + u * wu + d * wd) / (wc + wl + wr + wu + wd);
    vec3 mx = max(max(max(l, r), max(u, d)), c), mn = min(min(min(l, r), min(u, d)), c);
    if (pc.t0.y > 0.0) {
        vec3 cas = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-3)), 0.0, 1.0));
        o += pc.t0.y * cas * (c - 0.25 * (l + r + u + d));
    }
    if (pc.t0.z < 0.99) { vec3 m = pc.t0.z * (mx - mn); o = clamp(o, mn - m, mx + m); }
    out_Target0 = vec4(clamp(o, 0.0, 1.0), 1.0);
}
