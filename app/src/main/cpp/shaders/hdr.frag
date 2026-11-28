#version 450
// SDR to HDR10: expands the highlights of an SDR picture and encodes it for an HDR10 (BT.2020, PQ) screen.
//   p.x = paper white in nits (what SDR white 1.0 becomes), p.y = peak brightness of the screen in nits,
//   p.z = highlight expansion strength 0..1, p.w = 1 when the sampled picture is already linear (sRGB image format)
// Colours keep their hue: the picture is scaled by a luminance-dependent gain, so only bright parts get brighter.
//
// Light version: this runs on every pixel of the screen every frame, so it avoids pow() altogether.
//   - the PQ curve is a 129-entry table indexed by the 4th root of the brightness (linear interpolation, worst error 0.25 of a 10-bit step,
//     measured against the exact ST 2084 formula); the 4th root is two square roots, where the exact curve needs about 20 pow/exp/log calls
//   - sRGB to linear (only when the image is not an sRGB format) is a quartic fit with the exact linear toe, and the gain is y*y*y
//   - the dither is interleaved gradient noise (no sin)
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { vec4 p; } pc;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 outColor;

const float PQ[129] = float[129](
    0.0000007, 0.0005443, 0.0027737, 0.0070006, 0.0130799, 0.0207327, 0.0296680, 0.0396240,
    0.0503776, 0.0617440, 0.0735721, 0.0857385, 0.0981434, 0.1107057, 0.1233597, 0.1360522,
    0.1487401, 0.1613886, 0.1739697, 0.1864607, 0.1988437, 0.2111042, 0.2232309, 0.2352152,
    0.2470501, 0.2587308, 0.2702537, 0.2816164, 0.2928175, 0.3038563, 0.3147329, 0.3254480,
    0.3360024, 0.3463978, 0.3566357, 0.3667181, 0.3766471, 0.3864252, 0.3960545, 0.4055378,
    0.4148775, 0.4240762, 0.4331366, 0.4420613, 0.4508530, 0.4595142, 0.4680477, 0.4764559,
    0.4847416, 0.4929071, 0.5009550, 0.5088877, 0.5167076, 0.5244171, 0.5320185, 0.5395140,
    0.5469059, 0.5541963, 0.5613874, 0.5684811, 0.5754795, 0.5823847, 0.5891984, 0.5959226,
    0.6025592, 0.6091098, 0.6155762, 0.6219602, 0.6282634, 0.6344874, 0.6406337, 0.6467040,
    0.6526996, 0.6586222, 0.6644730, 0.6702534, 0.6759649, 0.6816088, 0.6871862, 0.6926985,
    0.6981470, 0.7035327, 0.7088568, 0.7141206, 0.7193250, 0.7244713, 0.7295603, 0.7345932,
    0.7395710, 0.7444946, 0.7493650, 0.7541831, 0.7589499, 0.7636662, 0.7683329, 0.7729508,
    0.7775208, 0.7820437, 0.7865203, 0.7909514, 0.7953376, 0.7996798, 0.8039787, 0.8082350,
    0.8124493, 0.8166224, 0.8207549, 0.8248474, 0.8289006, 0.8329151, 0.8368915, 0.8408305,
    0.8447325, 0.8485981, 0.8524280, 0.8562226, 0.8599825, 0.8637082, 0.8674002, 0.8710590,
    0.8746851, 0.8782790, 0.8818411, 0.8853720, 0.8888720, 0.8923416, 0.8957813, 0.8991914,
    0.9025724
);

vec3 srgb_to_linear(vec3 c) {                     // exact sRGB curve: linear toe below 0.04045, a quartic fit above it (within 0.5 %), black stays black
    vec3 q = 0.001008 + c * (0.029762 + c * (0.549281 + c * (0.553146 - c * 0.134942)));
    return mix(q, c * (1.0 / 12.92), lessThan(c, vec3(0.04045)));
}
vec3 pq_encode(vec3 nits) {                       // SMPTE ST 2084 inverse EOTF for 0..4000 nits, 0..1 out
    vec3 u = sqrt(sqrt(clamp(nits, 0.0, 4000.0) * (1.0 / 4000.0))) * 128.0;
    ivec3 i = min(ivec3(u), ivec3(127));
    vec3 f = u - vec3(i);
    return vec3(mix(PQ[i.x], PQ[i.x + 1], f.x), mix(PQ[i.y], PQ[i.y + 1], f.y), mix(PQ[i.z], PQ[i.z + 1], f.z));
}

void main() {
    vec3 c = clamp(texture(tex, uv).rgb, 0.0, 1.0);
    vec3 lin = pc.p.w > 0.5 ? c : srgb_to_linear(c);
    float y = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    float headroom = max(pc.p.y / pc.p.x - 1.0, 0.0);              // how much brighter than paper white the screen can go
    float gain = 1.0 + headroom * pc.p.z * (y * y * y);            // dark and mid tones stay where they were
    vec3 nits = lin * gain * pc.p.x;
    nits = min(nits, vec3(pc.p.y));
    // BT.709 -> BT.2020 primaries
    vec3 rec2020 = vec3(dot(nits, vec3(0.6274, 0.3293, 0.0433)),
                        dot(nits, vec3(0.0691, 0.9195, 0.0114)),
                        dot(nits, vec3(0.0164, 0.0880, 0.8956)));
    vec3 e = pq_encode(rec2020);
    float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;   // about one 10-bit step of dither
    outColor = vec4(clamp(e + n / 1023.0, 0.0, 1.0), 1.0);
}
