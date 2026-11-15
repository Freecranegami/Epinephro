#version 450
// SGSR Quality = Snapdragon Game Super Resolution 1, edge-direction variant, ported from sgsr1_shader_mobile_edge_direction.frag in
// github.com/SnapdragonGameStudios/snapdragon-gsr:
//   Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.  SPDX-License-Identifier: BSD-3-Clause
// Same single pass, inputs and push constant as sgsr.frag (shares its vertex shader). Differences from sgsr.frag, both from the
// reference file: the Lanczos weights are stretched along the local edge direction (cleaner diagonal edges), and the contrast term
// is std = (10.14185 / sum)^2. Operation mode RGBA (mode 1), edge threshold 8/255, edge sharpness 2.
layout(set = 0, binding = 0) uniform sampler2D ps0;
layout(push_constant) uniform PC { highp vec4 ViewportInfo; } pc;
layout(location = 0) in highp vec2 uv;
layout(location = 0) out vec4 out_Target0;

// Half precision for the colour maths (Adreno runs it at about twice the ALU rate). The contrast term (it can pass 65504) and the
// edge direction (its 3.07e-05 bias is below the smallest normal half) stay full precision.
precision mediump float;
const float EdgeThreshold = 8.0 / 255.0;
const float EdgeSharpness = 2.0;

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}
// data.x = std, data.yz = normalised edge direction
vec2 weightY(float dx, float dy, float c, highp vec3 data) {
    highp float edgeDis = (dx * data.z) + (dy * data.y);
    highp float x = ((dx * dx) + (dy * dy)) + ((edgeDis * edgeDis) * ((clamp((c * c) * data.x, 0.0, 1.0) * 0.7) - 1.0));
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}
highp vec2 edgeDirection(vec4 left, vec4 right) {
    highp float RxLz = right.x - left.z;
    highp float RwLy = right.w - left.y;
    highp vec2 delta;
    delta.x = RxLz + RwLy;
    delta.y = RxLz - RwLy;
    highp float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05) + (delta.y * delta.y));
    return delta * lengthInv;
}

void main() {
    vec4 color;
    color.xyz = textureLod(ps0, uv, 0.0).xyz;
    color.w = 0.0;

    highp vec2 imgCoord = (uv * pc.ViewportInfo.zw) + vec2(-0.5, 0.5);
    highp vec2 imgCoordPixel = floor(imgCoord);
    highp vec2 coord = imgCoordPixel * pc.ViewportInfo.xy;
    vec2 pl = imgCoord - imgCoordPixel;
    vec4 left = textureGather(ps0, coord, 1);

    float edgeVote = abs(left.z - left.y) + abs(color.y - left.y) + abs(color.y - left.z);
    if (edgeVote > EdgeThreshold) {
        coord.x += pc.ViewportInfo.x;

        vec4 right = textureGather(ps0, coord + vec2(pc.ViewportInfo.x, 0.0), 1);
        vec4 upDown;
        upDown.xy = textureGather(ps0, coord + vec2(0.0, -pc.ViewportInfo.y), 1).wz;
        upDown.zw = textureGather(ps0, coord + vec2(0.0, pc.ViewportInfo.y), 1).yx;

        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left = left - vec4(mean);
        right = right - vec4(mean);
        upDown = upDown - vec4(mean);
        color.w = color.y - mean;

        highp float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w)) + (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w))) + (((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w)));
        highp float sumMean = 1.014185e+01 / sum;
        highp float std = sumMean * sumMean;
        highp vec3 data = vec3(std, edgeDirection(left, right));

        vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, data);
        aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, data);
        aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, data);
        aWY += weightY(pl.x, pl.y - 2.0, upDown.w, data);
        aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, data);
        aWY += weightY(pl.x, pl.y - 1.0, left.y, data);
        aWY += weightY(pl.x, pl.y, left.z, data);
        aWY += weightY(pl.x + 1.0, pl.y, left.w, data);
        aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, data);
        aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, data);
        aWY += weightY(pl.x - 2.0, pl.y, right.z, data);
        aWY += weightY(pl.x - 1.0, pl.y, right.w, data);

        float finalY = aWY.y / aWY.x;

        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        finalY = clamp(EdgeSharpness * finalY, minY, maxY);

        float deltaY = finalY - color.w;
        // smooth high contrast input
        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

        color.x = clamp(color.x + deltaY, 0.0, 1.0);
        color.y = clamp(color.y + deltaY, 0.0, 1.0);
        color.z = clamp(color.z + deltaY, 0.0, 1.0);
    }
    out_Target0 = vec4(color.xyz, 1.0);
}
