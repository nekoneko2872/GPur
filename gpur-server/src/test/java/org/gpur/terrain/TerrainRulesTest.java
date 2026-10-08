package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TerrainRulesTest {
    @Test void wireFormatBoundsAllocationAndRejectsMalformedBatches() {
        int[] input = TerrainRules.input(0x12345678abcdef01L, -64, 512, 63,
            -1, 0, 0, -1, 1, 0, 0, 1, 1, 1, -1, 1, 1, -1, 0, 0);
        assertEquals(3, input[0]);
        assertEquals(8 * 256, input[1]);
        assertEquals(0xabcdef01, input[2]);
        assertEquals(0x12345678, input[3]);
        assertEquals(TerrainRules.VERSION, input[6]);
        assertEquals(8 * 512 * 256, TerrainRules.outputWords(input));

        assertThrows(IllegalArgumentException.class, () -> TerrainRules.input(1L, 0, 64, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TerrainRules.input(1L, 0, 48, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TerrainRules.input(1L, 0, 64, 64, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TerrainRules.input(1L, 0, 64, 0, 1_875_000, 0));
        int[] malformed = TerrainRules.input(1L, 0, 64, 0, 0, 0);
        malformed[1]++;
        assertThrows(IllegalArgumentException.class, () -> TerrainRules.validate(malformed));
    }

    @Test void outputIsDeterministicAcrossNegativeAndWorldBorderCoordinatesAndUsesAllSeedBits() {
        int[] input = TerrainRules.input(0x0000000000000000L, -32, 64, 0,
            -1_874_999, 1_874_999, 1_874_999, -1_874_999);
        int[] first = TerrainRules.reference(input);
        assertArrayEquals(first, TerrainRules.reference(input));

        int[] changedHighWord = TerrainRules.input(0x0000000100000000L, -32, 64, 0,
            -1_874_999, 1_874_999, 1_874_999, -1_874_999);
        assertFalse(java.util.Arrays.equals(first, TerrainRules.reference(changedHighWord)));
        for (int block : first) assertTrue(block >= 0 && block < TerrainRules.PALETTE_SIZE);
    }

    @Test void singleColumnMatchesChunkReferenceAtNegativeAndFarCoordinates() {
        long seed = 0xfedcba9876543210L;
        int minY = -64;
        int height = 128;
        int seaLevel = 0;
        int[] chunks = {-1_874_999, -1_874_999, 1_874_999, 1_874_999};
        int[] full = TerrainRules.reference(TerrainRules.input(seed, minY, height, seaLevel, chunks));
        int[][] localColumns = {{0, 0}, {15, 15}, {7, 9}};

        for (int chunk = 0; chunk < chunks.length / 2; chunk++) {
            for (int[] local : localColumns) {
                int localX = local[0];
                int localZ = local[1];
                int worldX = chunks[chunk * 2] * 16 + localX;
                int worldZ = chunks[chunk * 2 + 1] * 16 + localZ;
                int[] single = TerrainRules.column(seed, worldX, worldZ, minY, height, seaLevel);
                for (int dy = 0; dy < height; dy++) {
                    int chunkIndex = (chunk * height + dy) * 256 + localZ * 16 + localX;
                    assertEquals(full[chunkIndex], single[dy], "column palette mismatch at y=" + (minY + dy));
                }
            }
        }
    }

    @Test void stonyPeaksUseStoneThroughTheSurfaceLayers() {
        long seed = 0x7a11ce55L;
        int minY = -64;
        int height = 128;
        int seaLevel = 0;
        boolean foundPeak = false;
        for (int x = -16 * 8_192; x <= 16 * 8_192 && !foundPeak; x += 8_192) {
            for (int z = -16 * 8_192; z <= 16 * 8_192; z += 8_192) {
                int surface = TerrainRules.surfaceHeight(seed, x, z, minY, height, seaLevel);
                if (TerrainRules.biome(seed, x, z, minY, height, seaLevel) != TerrainRules.STONY_PEAKS) continue;
                int[] column = TerrainRules.column(seed, x, z, minY, height, seaLevel);
                for (int depth = 0; depth <= 3; depth++) {
                    assertEquals(TerrainRules.STONE, column[surface - depth - minY]);
                }
                foundPeak = true;
                break;
            }
        }
        assertTrue(foundPeak, "sampled terrain should include a stony peak");
    }

    @Test void adjacentColumnsStayContinuousAndTerrainContainsWaterAndCarvedCaves() {
        long seed = 0x5eed1234cafebabeL;
        int minY = -64;
        int height = 128;
        int seaLevel = 0;
        int waterColumns = 0;
        int caveAir = 0;
        int landColumns = 0;
        int[] chunkCoordinates = {-1, 0, 0, 0, 200, 250, -200, -250,
            1_000, -700, -1_000, 800, 2_000, 2_000, -2_000, -2_000};
        int[] blocks = TerrainRules.reference(TerrainRules.input(seed, minY, height, seaLevel, chunkCoordinates));

        for (int chunk = 0; chunk < chunkCoordinates.length / 2; chunk++) {
            int chunkX = chunkCoordinates[chunk * 2];
            int chunkZ = chunkCoordinates[chunk * 2 + 1];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;
                    int surface = TerrainRules.surfaceHeight(seed, worldX, worldZ, minY, height, seaLevel);
                    if (surface < seaLevel) {
                        waterColumns++;
                        int seaIndex = (chunk * height + (seaLevel - minY)) * 256 + z * 16 + x;
                        assertEquals(TerrainRules.WATER, blocks[seaIndex]);
                    } else {
                        landColumns++;
                    }
                    if (x < 15) {
                        int neighbor = TerrainRules.surfaceHeight(seed, worldX + 1, worldZ, minY, height, seaLevel);
                        assertTrue(Math.abs(surface - neighbor) <= 8, "adjacent x columns should have a connected profile");
                    }
                    if (z < 15) {
                        int neighbor = TerrainRules.surfaceHeight(seed, worldX, worldZ + 1, minY, height, seaLevel);
                        assertTrue(Math.abs(surface - neighbor) <= 8, "adjacent z columns should have a connected profile");
                    }
                    for (int y = minY + 5; y < surface - 4; y++) {
                        int chunkIndex = chunk;
                        int index = (chunkIndex * height + (y - minY)) * 256 + z * 16 + x;
                        if (blocks[index] == TerrainRules.AIR) caveAir++;
                    }
                    int floorIndex = (chunk * height) * 256 + z * 16 + x;
                    assertEquals(TerrainRules.BEDROCK, blocks[floorIndex]);
                }
            }
        }
        // The first two chunks share an edge, so verify continuity across it too.
        for (int z = 0; z < 16; z++) {
            int left = TerrainRules.surfaceHeight(seed, -1, z, minY, height, seaLevel);
            int right = TerrainRules.surfaceHeight(seed, 0, z, minY, height, seaLevel);
            assertTrue(Math.abs(left - right) <= 8);
        }
        assertTrue(waterColumns > 0);
        assertTrue(landColumns > 0);
        assertTrue(caveAir > 0);
    }
}
