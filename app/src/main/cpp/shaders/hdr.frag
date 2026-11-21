#version 450
// SDR to HDR10: expands the highlights of an SDR picture and encodes it for an HDR10 (BT.2020, PQ) screen.
//   p.x = paper white in nits (what SDR white 1.0 becomes), p.y = peak brightness of the screen in nits,
//   p.z = highlight expansion strength 0..1, p.w = 1 when the sampled picture is already linear (sRGB image format)
// Colours keep their hue: the picture is scaled by a luminance-dependent gain, so only bright parts get brighter.
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { vec4 p; } pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

vec3 srgb_to_linear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}
vec3 pq_encode(vec3 nits) {                       // SMPTE ST 2084 inverse EOTF, 0..10000 nits -> 0..1
    const float m1 = 0.1593017578125, m2 = 78.84375, c1 = 0.8359375, c2 = 18.8515625, c3 = 18.6875;
    vec3 y = pow(clamp(nits / 10000.0, 0.0, 1.0), vec3(m1));
    return pow((c1 + c2 * y) / (1.0 + c3 * y), vec3(m2));
}
float hash(vec2 q) { return fract(sin(dot(q, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    vec3 c = clamp(texture(tex, uv).rgb, 0.0, 1.0);
    vec3 lin = pc.p.w > 0.5 ? c : srgb_to_linear(c);
    float y = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    float headroom = max(pc.p.y / pc.p.x - 1.0, 0.0);              // how much brighter than paper white the screen can go
    float gain = 1.0 + headroom * pc.p.z * pow(clamp(y, 0.0, 1.0), 3.0);   // dark and mid tones stay where they were
    vec3 nits = lin * gain * pc.p.x;
    nits = min(nits, vec3(pc.p.y));
    // BT.709 -> BT.2020 primaries
    vec3 rec2020 = vec3(dot(nits, vec3(0.6274, 0.3293, 0.0433)),
                        dot(nits, vec3(0.0691, 0.9195, 0.0114)),
                        dot(nits, vec3(0.0164, 0.0880, 0.8956)));
    vec3 e = pq_encode(rec2020);
    float n = hash(gl_FragCoord.xy) + hash(gl_FragCoord.xy + 17.0) - 1.0;   // triangular dither, about one 10-bit step
    outColor = vec4(clamp(e + n / 1023.0, 0.0, 1.0), 1.0);
}
