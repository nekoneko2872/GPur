// Pure helpers for workload 5. The caller supplies data[], readDouble(), and
// preciseLerp() before concatenating this file; all profile offsets are absolute word offsets.

const uint VN_IMPROVED = 1u;
const uint VN_PERLIN = 2u;
const uint VN_NORMAL = 3u;
const uint VN_BLENDED = 4u;
const uint VN_PERLIN_HEADER_WORDS = 7u;
const uint VN_PERLIN_OCTAVE_WORDS = 3u;
const double VN_NORMAL_INPUT_FACTOR = 1.0181268882175227;
const double VN_PERLIN_WRAP = 33554432.0;

uint vnPermutation(uint profileOffset, int index) {
    uint wrapped = uint(index) & 255u;
    uint packed = data[profileOffset + 8u + (wrapped >> 2u)];
    return (packed >> ((wrapped & 3u) * 8u)) & 255u;
}

int vnGradientX(uint gradient) {
    switch (gradient) {
        case 0u: case 2u: case 4u: case 6u: case 12u: return 1;
        case 1u: case 3u: case 5u: case 7u: case 14u: return -1;
        default: return 0;
    }
}

int vnGradientY(uint gradient) {
    switch (gradient) {
        case 0u: case 1u: case 8u: case 10u: case 12u: case 14u: return 1;
        case 2u: case 3u: case 9u: case 11u: case 13u: case 15u: return -1;
        default: return 0;
    }
}

int vnGradientZ(uint gradient) {
    switch (gradient) {
        case 4u: case 5u: case 8u: case 9u: case 13u: return 1;
        case 6u: case 7u: case 10u: case 11u: case 15u: return -1;
        default: return 0;
    }
}

precise double vnGradientDot(uint hash, double x, double y, double z) {
    uint gradient = hash & 15u;
    precise double xPart = double(vnGradientX(gradient)) * x;
    precise double yPart = double(vnGradientY(gradient)) * y;
    precise double xy = xPart + yPart;
    precise double zPart = double(vnGradientZ(gradient)) * z;
    precise double resultValue = xy + zPart;
    return resultValue;
}

precise double vnSmoothstep(double value) {
    precise double squared = value * value;
    precise double cubed = squared * value;
    precise double sixX = value * 6.0;
    precise double inner = sixX - 15.0;
    precise double scaledInner = value * inner;
    precise double curve = scaledInner + 10.0;
    precise double resultValue = cubed * curve;
    return resultValue;
}

precise double vnLerp2(double x, double y, double n00, double n10, double n01, double n11) {
    precise double lower = preciseLerp(x, n00, n10);
    precise double upper = preciseLerp(x, n01, n11);
    precise double resultValue = preciseLerp(y, lower, upper);
    return resultValue;
}

precise double vnLerp3(
    double x,
    double y,
    double z,
    double n000,
    double n100,
    double n010,
    double n110,
    double n001,
    double n101,
    double n011,
    double n111
) {
    precise double lower = vnLerp2(x, y, n000, n100, n010, n110);
    precise double upper = vnLerp2(x, y, n001, n101, n011, n111);
    precise double resultValue = preciseLerp(z, lower, upper);
    return resultValue;
}

precise double vnImprovedNoise(
    uint profileOffset,
    double sampleX,
    double sampleY,
    double sampleZ,
    double yScale,
    double yFudge
) {
    precise double x = sampleX + readDouble(profileOffset + 2u);
    precise double y = sampleY + readDouble(profileOffset + 4u);
    precise double z = sampleZ + readDouble(profileOffset + 6u);
    int xf = int(floor(x));
    int yf = int(floor(y));
    int zf = int(floor(z));
    precise double xr = x - double(xf);
    precise double yr = y - double(yf);
    precise double zr = z - double(zf);
    precise double yrFudge;
    if (yScale != 0.0) {
        precise double fudgeLimit;
        if (yFudge >= 0.0 && yFudge < yr) {
            fudgeLimit = yFudge;
        } else {
            fudgeLimit = yr;
        }
        precise double floorInput = fudgeLimit / yScale + double(1.0e-7f);
        precise double floorValue = floor(floorInput);
        yrFudge = floorValue * yScale;
    } else {
        yrFudge = 0.0;
    }

    int x0 = int(vnPermutation(profileOffset, xf));
    int x1 = int(vnPermutation(profileOffset, xf + 1));
    int xy00 = int(vnPermutation(profileOffset, x0 + yf));
    int xy01 = int(vnPermutation(profileOffset, x0 + yf + 1));
    int xy10 = int(vnPermutation(profileOffset, x1 + yf));
    int xy11 = int(vnPermutation(profileOffset, x1 + yf + 1));
    double d000 = vnGradientDot(vnPermutation(profileOffset, xy00 + zf), xr, yr - yrFudge, zr);
    double d100 = vnGradientDot(vnPermutation(profileOffset, xy10 + zf), xr - 1.0, yr - yrFudge, zr);
    double d010 = vnGradientDot(vnPermutation(profileOffset, xy01 + zf), xr, (yr - yrFudge) - 1.0, zr);
    double d110 = vnGradientDot(vnPermutation(profileOffset, xy11 + zf), xr - 1.0, (yr - yrFudge) - 1.0, zr);
    double d001 = vnGradientDot(vnPermutation(profileOffset, xy00 + zf + 1), xr, yr - yrFudge, zr - 1.0);
    double d101 = vnGradientDot(vnPermutation(profileOffset, xy10 + zf + 1), xr - 1.0, yr - yrFudge, zr - 1.0);
    double d011 = vnGradientDot(vnPermutation(profileOffset, xy01 + zf + 1), xr, (yr - yrFudge) - 1.0, zr - 1.0);
    double d111 = vnGradientDot(vnPermutation(profileOffset, xy11 + zf + 1), xr - 1.0, (yr - yrFudge) - 1.0, zr - 1.0);
    precise double xAlpha = vnSmoothstep(xr);
    precise double yAlpha = vnSmoothstep(yr);
    precise double zAlpha = vnSmoothstep(zr);
    return vnLerp3(xAlpha, yAlpha, zAlpha, d000, d100, d010, d110, d001, d101, d011, d111);
}

precise double vnWrapPerlin(double value) {
    precise double turns = floor(value / VN_PERLIN_WRAP + 0.5);
    precise double scaledTurns = turns * VN_PERLIN_WRAP;
    precise double resultValue = value - scaledTurns;
    return resultValue;
}

precise double vnPerlinNoise(
    uint profileOffset,
    double x,
    double y,
    double z,
    double yScale,
    double yFudge
) {
    uint octaveCount = data[profileOffset + 2u];
    precise double value = 0.0;
    precise double frequency = readDouble(profileOffset + 3u);
    precise double valueFactor = readDouble(profileOffset + 5u);
    for (uint octave = 0u; octave < octaveCount; octave++) {
        uint octaveOffset = profileOffset + VN_PERLIN_HEADER_WORDS + octave * VN_PERLIN_OCTAVE_WORDS;
        uint noiseOffset = data[octaveOffset + 2u];
        if (noiseOffset != 0xffffffffu) {
            precise double xInput = vnWrapPerlin(x * frequency);
            precise double yInput = vnWrapPerlin(y * frequency);
            precise double zInput = vnWrapPerlin(z * frequency);
            precise double scaleInput = yScale * frequency;
            precise double fudgeInput = yFudge * frequency;
            precise double noiseValue = vnImprovedNoise(noiseOffset, xInput, yInput, zInput, scaleInput, fudgeInput);
            precise double amplitudeValue = readDouble(octaveOffset);
            precise double weighted = amplitudeValue * noiseValue;
            weighted = weighted * valueFactor;
            value = value + weighted;
        }
        frequency = frequency * 2.0;
        valueFactor = valueFactor / 2.0;
    }
    return value;
}

precise double vnNormalNoise(uint profileOffset, double x, double y, double z) {
    uint firstOffset = data[profileOffset + 2u];
    uint secondOffset = data[profileOffset + 3u];
    precise double first = vnPerlinNoise(firstOffset, x, y, z, 0.0, 0.0);
    precise double scaledX = x * VN_NORMAL_INPUT_FACTOR;
    precise double scaledY = y * VN_NORMAL_INPUT_FACTOR;
    precise double scaledZ = z * VN_NORMAL_INPUT_FACTOR;
    precise double second = vnPerlinNoise(secondOffset, scaledX, scaledY, scaledZ, 0.0, 0.0);
    precise double sum = first + second;
    precise double valueFactor = readDouble(profileOffset + 4u);
    precise double resultValue = sum * valueFactor;
    return resultValue;
}

uint vnOctaveNoiseOffset(uint profileOffset, uint octave) {
    uint octaveCount = data[profileOffset + 2u];
    if (octave >= octaveCount) return 0xffffffffu;
    uint arrayIndex = octaveCount - 1u - octave;
    uint octaveOffset = profileOffset + VN_PERLIN_HEADER_WORDS + arrayIndex * VN_PERLIN_OCTAVE_WORDS;
    return data[octaveOffset + 2u];
}

precise double vnBlendedNoise(uint profileOffset, double x, double y, double z) {
    uint minLimitOffset = data[profileOffset + 2u];
    uint maxLimitOffset = data[profileOffset + 3u];
    uint mainOffset = data[profileOffset + 4u];
    precise double xzMultiplier = readDouble(profileOffset + 5u);
    precise double yMultiplier = readDouble(profileOffset + 7u);
    precise double xzFactor = readDouble(profileOffset + 9u);
    precise double yFactor = readDouble(profileOffset + 11u);
    precise double smearScaleMultiplier = readDouble(profileOffset + 13u);
    precise double limitX = x * xzMultiplier;
    precise double limitY = y * yMultiplier;
    precise double limitZ = z * xzMultiplier;
    precise double mainX = limitX / xzFactor;
    precise double mainY = limitY / yFactor;
    precise double mainZ = limitZ / xzFactor;
    precise double limitSmear = yMultiplier * smearScaleMultiplier;
    precise double mainSmear = limitSmear / yFactor;
    precise double blendMin = 0.0;
    precise double blendMax = 0.0;
    precise double mainNoiseValue = 0.0;
    precise double powValue = 1.0;

    for (uint i = 0u; i < 8u; i++) {
        uint noiseOffset = vnOctaveNoiseOffset(mainOffset, i);
        if (noiseOffset != 0xffffffffu) {
            precise double wx = vnWrapPerlin(mainX * powValue);
            precise double wy = vnWrapPerlin(mainY * powValue);
            precise double wz = vnWrapPerlin(mainZ * powValue);
            precise double scale = mainSmear * powValue;
            precise double fudge = mainY * powValue;
            precise double noiseValue = vnImprovedNoise(noiseOffset, wx, wy, wz, scale, fudge);
            precise double octaveValue = noiseValue / powValue;
            mainNoiseValue = mainNoiseValue + octaveValue;
        }
        powValue = powValue / 2.0;
    }

    precise double factorNumerator = mainNoiseValue / 10.0 + 1.0;
    precise double factor = factorNumerator / 2.0;
    bool isMax = factor >= 1.0;
    bool isMin = factor <= 0.0;
    powValue = 1.0;
    for (uint i = 0u; i < 16u; i++) {
        precise double wx = vnWrapPerlin(limitX * powValue);
        precise double wy = vnWrapPerlin(limitY * powValue);
        precise double wz = vnWrapPerlin(limitZ * powValue);
        precise double yScalePow = limitSmear * powValue;
        if (!isMax) {
            uint noiseOffset = vnOctaveNoiseOffset(minLimitOffset, i);
            if (noiseOffset != 0xffffffffu) {
                precise double yFudgePow = limitY * powValue;
                precise double noiseValue = vnImprovedNoise(noiseOffset, wx, wy, wz, yScalePow, yFudgePow);
                precise double octaveValue = noiseValue / powValue;
                blendMin = blendMin + octaveValue;
            }
        }
        if (!isMin) {
            uint noiseOffset = vnOctaveNoiseOffset(maxLimitOffset, i);
            if (noiseOffset != 0xffffffffu) {
                precise double yFudgePow = limitY * powValue;
                precise double noiseValue = vnImprovedNoise(noiseOffset, wx, wy, wz, yScalePow, yFudgePow);
                precise double octaveValue = noiseValue / powValue;
                blendMax = blendMax + octaveValue;
            }
        }
        powValue = powValue / 2.0;
    }

    precise double minValue = blendMin / 512.0;
    precise double maxValue = blendMax / 512.0;
    precise double clamped;
    if (factor < 0.0) {
        clamped = minValue;
    } else if (factor > 1.0) {
        clamped = maxValue;
    } else {
        clamped = preciseLerp(factor, minValue, maxValue);
    }
    return clamped / 128.0;
}

precise double evaluateVanillaNoise(uint profileOffset, double x, double y, double z, double yScale, double yFudge) {
    uint type = data[profileOffset];
    if (type == VN_IMPROVED) return vnImprovedNoise(profileOffset, x, y, z, yScale, yFudge);
    if (type == VN_PERLIN) return vnPerlinNoise(profileOffset, x, y, z, yScale, yFudge);
    if (type == VN_NORMAL) return vnNormalNoise(profileOffset, x, y, z);
    if (type == VN_BLENDED) return vnBlendedNoise(profileOffset, x, y, z);
    return 0.0;
}
