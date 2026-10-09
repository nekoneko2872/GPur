// Exact integer ranking of the twelve vanilla NoiseBasedAquifer candidates.
// data[] is a bounded snapshot: header, XYZ block queries, then XYZ center tuples.
// No world, random source, fluid state, or mutable aquifer cache is accessed here.

const uint AQU_HEADER_WORDS = 8u;

int aquiferSignedBits(uint bits) {
    if (bits <= 0x7fffffffu) return int(bits);
    return -1 - int(~bits);
}

int aquiferFloorDiv12(int value) {
    int quotient = value / 12;
    int remainder = value % 12;
    return value < 0 && remainder != 0 ? quotient - 1 : quotient;
}

uint aquiferIntBits(int value) {
    if (value >= 0) return uint(value);
    return ~uint(-value - 1);
}

uvec4 rankVanillaAquifer(uint query) {
    int minGridX = aquiferSignedBits(data[2u]);
    int minGridY = aquiferSignedBits(data[3u]);
    int minGridZ = aquiferSignedBits(data[4u]);
    uint gridSizeX = data[5u];
    uint gridSizeZ = data[7u];
    uint queryOffset = AQU_HEADER_WORDS + query * 3u;
    uint queryCount = data[1u];
    uint centersOffset = AQU_HEADER_WORDS + queryCount * 3u;
    int blockX = aquiferSignedBits(data[queryOffset]);
    int blockY = aquiferSignedBits(data[queryOffset + 1u]);
    int blockZ = aquiferSignedBits(data[queryOffset + 2u]);
    int anchorX = (blockX - 5) >> 4;
    int anchorY = aquiferFloorDiv12(blockY + 1);
    int anchorZ = (blockZ - 5) >> 4;

    int distance1 = 2147483647;
    int distance2 = 2147483647;
    int distance3 = 2147483647;
    int distance4 = 2147483647;
    uint index1 = 0u;
    uint index2 = 0u;
    uint index3 = 0u;
    uint index4 = 0u;

    // Match computeSubstance visitation order and >= tie insertion exactly.
    for (int xOffset = 0; xOffset <= 1; xOffset++) {
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int zOffset = 0; zOffset <= 1; zOffset++) {
                int gridX = anchorX + xOffset;
                int gridY = anchorY + yOffset;
                int gridZ = anchorZ + zOffset;
                uint relativeX = uint(gridX - minGridX);
                uint relativeY = uint(gridY - minGridY);
                uint relativeZ = uint(gridZ - minGridZ);
                uint cellIndex = (relativeY * gridSizeZ + relativeZ) * gridSizeX + relativeX;
                uint centerOffset = centersOffset + cellIndex * 3u;
                int dx = aquiferSignedBits(data[centerOffset]) - blockX;
                int dy = aquiferSignedBits(data[centerOffset + 1u]) - blockY;
                int dz = aquiferSignedBits(data[centerOffset + 2u]) - blockZ;

                // Java's vanilla code squares and sums as signed int, including wraparound.
                uint dxBits = aquiferIntBits(dx);
                uint dyBits = aquiferIntBits(dy);
                uint dzBits = aquiferIntBits(dz);
                uint distanceBits = dxBits * dxBits + dyBits * dyBits + dzBits * dzBits;
                int distance = aquiferSignedBits(distanceBits);
                if (distance1 >= distance) {
                    index4 = index3;
                    index3 = index2;
                    index2 = index1;
                    index1 = cellIndex;
                    distance4 = distance3;
                    distance3 = distance2;
                    distance2 = distance1;
                    distance1 = distance;
                } else if (distance2 >= distance) {
                    index4 = index3;
                    index3 = index2;
                    index2 = cellIndex;
                    distance4 = distance3;
                    distance3 = distance2;
                    distance2 = distance;
                } else if (distance3 >= distance) {
                    index4 = index3;
                    index3 = cellIndex;
                    distance4 = distance3;
                    distance3 = distance;
                } else if (distance4 >= distance) {
                    index4 = cellIndex;
                    distance4 = distance;
                }
            }
        }
    }

    return uvec4(index1, index2, index3, index4);
}
