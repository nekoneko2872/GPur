package org.gpur.terrain;

/**
 * Versioned, integer-only terrain rules shared by the CPU fallback and compute shader.
 * Version 1 uses a base two blocks above sea level, broad continental/hill/detail fields and two
 * 3D Q10 fields for caves. Output is chunk-major, then y, then z, then x.
 */
public final class TerrainRules {
    public static final int VERSION = 1;
    public static final int MAX_BATCH_CHUNKS = 8;
    public static final int PALETTE_SIZE = 13;
    public static final int MIN_HEIGHT = 64;
    public static final int MAX_HEIGHT = 512;
    public static final int MAX_CHUNK_COORD = 1_874_999;

    public static final int AIR = 0;
    public static final int STONE = 1;
    public static final int DEEPSLATE = 2;
    public static final int BEDROCK = 3;
    public static final int WATER = 4;
    public static final int LAVA = 5;
    public static final int DIRT = 6;
    public static final int GRASS_BLOCK = 7;
    public static final int SAND = 8;
    public static final int SANDSTONE = 9;
    public static final int GRAVEL = 10;
    public static final int SNOW_BLOCK = 11;
    public static final int ICE = 12;

    public static final int PLAINS = 0;
    public static final int FOREST = 1;
    public static final int DESERT = 2;
    public static final int TAIGA = 3;
    public static final int SNOWY_PLAINS = 4;
    public static final int OCEAN = 5;
    public static final int MEADOW = 6;
    public static final int STONY_PEAKS = 7;

    private static final int Q = 1024;
    private static final int WORKLOAD = 3;

    private TerrainRules() {}

    /** Build workload 3 input words from (chunkX, chunkZ) pairs. */
    public static int[] input(long seed, int minY, int height, int seaLevel, int... chunkCoordinates) {
        if (chunkCoordinates == null || (chunkCoordinates.length & 1) != 0) {
            throw new IllegalArgumentException("Chunk coordinates must be x,z pairs");
        }
        int chunks = chunkCoordinates.length / 2;
        if (chunks < 1 || chunks > MAX_BATCH_CHUNKS) {
            throw new IllegalArgumentException("Terrain batches must contain 1 to 8 chunks");
        }
        int[] words = new int[8 + chunkCoordinates.length];
        words[0] = WORKLOAD;
        words[1] = chunks * 256;
        words[2] = (int)seed;
        words[3] = (int)(seed >>> 32);
        words[4] = minY;
        words[5] = height;
        words[6] = VERSION;
        words[7] = seaLevel;
        System.arraycopy(chunkCoordinates, 0, words, 8, chunkCoordinates.length);
        validate(words);
        return words;
    }

    /** Reject malformed or oversized workload 3 buffers before allocating an output. */
    public static void validate(int[] input) {
        if (input == null || input.length < 10 || input[0] != WORKLOAD || input[1] < 256 || input[1] % 256 != 0) {
            throw new IllegalArgumentException("Invalid terrain workload header");
        }
        int chunks = input[1] / 256;
        if (chunks > MAX_BATCH_CHUNKS || input.length != 8L + chunks * 2L) {
            throw new IllegalArgumentException("Invalid terrain batch dimensions");
        }
        validateGeometry(input[4], input[5], input[7]);
        if (input[6] != VERSION) throw new IllegalArgumentException("Unsupported terrain rules version");
        for (int i = 0; i < chunks; i++) {
            int x = input[8 + i * 2];
            int z = input[9 + i * 2];
            if (x < -MAX_CHUNK_COORD || x > MAX_CHUNK_COORD || z < -MAX_CHUNK_COORD || z > MAX_CHUNK_COORD) {
                throw new IllegalArgumentException("Chunk coordinate is outside the legal world border");
            }
        }
    }

    public static int outputWords(int[] input) {
        validate(input);
        return input[1] * input[5];
    }

    /** Generate the deterministic palette reference for a validated workload 3 buffer. */
    public static int[] reference(int[] input) {
        validate(input);
        int chunks = input[1] / 256;
        int minY = input[4];
        int height = input[5];
        int seaLevel = input[7];
        int[] output = new int[outputWords(input)];
        long seed = Integer.toUnsignedLong(input[2]) | ((long)input[3] << 32);
        for (int chunk = 0; chunk < chunks; chunk++) {
            int chunkX = input[8 + chunk * 2];
            int chunkZ = input[9 + chunk * 2];
            int chunkBase = chunk * height * 256;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int x = chunkX * 16 + localX;
                    int z = chunkZ * 16 + localZ;
                    fillColumn(seed, x, z, minY, height, seaLevel, output,
                        chunkBase + localZ * 16 + localX, 256);
                }
            }
        }
        return output;
    }

    /** Generate one column without allocating or computing any neighboring columns. */
    public static int[] column(long seed, int x, int z, int minY, int height, int seaLevel) {
        validateGeometry(minY, height, seaLevel);
        int[] output = new int[height];
        fillColumn(seed, x, z, minY, height, seaLevel, output, 0, 1);
        return output;
    }

    public static int surfaceHeight(long seed, int x, int z, int minY, int height, int seaLevel) {
        validateGeometry(minY, height, seaLevel);
        return surfaceHeightUnchecked(seed, x, z, minY, height, seaLevel);
    }

    /** Returns one of the fixed biome climate codes 0 through 7. */
    public static int biome(long seed, int x, int z, int minY, int height, int seaLevel) {
        validateGeometry(minY, height, seaLevel);
        int surface = surfaceHeightUnchecked(seed, x, z, minY, height, seaLevel);
        return biomeAt(seed, x, z, seaLevel, surface);
    }

    private static void validateGeometry(int minY, int height, int seaLevel) {
        if (minY < -2048 || minY > 2048 || height < MIN_HEIGHT || height > MAX_HEIGHT || (height & 15) != 0) {
            throw new IllegalArgumentException("Terrain height must be 64..512 in multiples of 16, with minY in -2048..2048");
        }
        long maxYExclusive = (long)minY + height;
        if (maxYExclusive > Integer.MAX_VALUE || seaLevel < minY || seaLevel >= maxYExclusive) {
            throw new IllegalArgumentException("Sea level must be inside the requested vertical range");
        }
    }

    private static int surfaceHeightUnchecked(long seed, int x, int z, int minY, int height, int seaLevel) {
        int lowSeed = (int)seed;
        int highSeed = (int)(seed >>> 32);
        int continental = noise2(lowSeed, highSeed, x, z, 768, 11);
        int hills = noise2(lowSeed, highSeed, x, z, 192, 23);
        int detail = noise2(lowSeed, highSeed, x, z, 48, 37);
        int mountain = Math.max(0, continental - 320);
        int natural = seaLevel + 2 + amplitude(continental, 42) + amplitude(hills, 24)
            + amplitude(detail, 6) + amplitude(mountain, 90);
        return clamp(natural, minY + 8, minY + height - 1);
    }

    private static void fillColumn(long seed, int x, int z, int minY, int height, int seaLevel,
                                   int[] output, int offset, int stride) {
        int surface = surfaceHeightUnchecked(seed, x, z, minY, height, seaLevel);
        int biome = biomeAt(seed, x, z, seaLevel, surface);
        for (int dy = 0; dy < height; dy++) {
            output[offset + dy * stride] = blockAt(seed, x, minY + dy, z, minY, seaLevel, surface, biome);
        }
    }

    private static int biomeAt(long seed, int x, int z, int seaLevel, int surface) {
        if (surface < seaLevel) return OCEAN;
        int continental = noise2((int)seed, (int)(seed >>> 32), x, z, 768, 11);
        int temperature = noise2((int)seed, (int)(seed >>> 32), x, z, 896, 301);
        int moisture = noise2((int)seed, (int)(seed >>> 32), x, z, 640, 302);
        if (surface >= seaLevel + 66 || (surface >= seaLevel + 38 && continental > 500)) return STONY_PEAKS;
        if (temperature < -430) return SNOWY_PLAINS;
        if (temperature > 390 && moisture < 120) return DESERT;
        if (temperature < -145) return TAIGA;
        if (surface > seaLevel + 28 && moisture > -180 && moisture < 260) return MEADOW;
        if (moisture > 145) return FOREST;
        return PLAINS;
    }

    private static int blockAt(long seed, int x, int y, int z, int minY, int seaLevel, int surface, int biome) {
        if (y == minY) return BEDROCK;
        if (y > surface) {
            if (y <= seaLevel) {
                if (y == seaLevel && biome == SNOWY_PLAINS && seaLevel - surface <= 8) return ICE;
                return WATER;
            }
            return AIR;
        }
        if (y < surface - 4 && y > minY + 4 && isCave(seed, x, y, z)) {
            return y < minY + 12 ? LAVA : AIR;
        }
        if (biome == DESERT) {
            if (y >= surface - 3) return SAND;
            if (y >= surface - 7) return SANDSTONE;
        } else if (biome == OCEAN) {
            if (surface < seaLevel - 20) {
                if (y == surface) return GRAVEL;
                if (y >= surface - 2) return GRAVEL;
            } else if (y >= surface - 1) {
                return SAND;
            }
        } else if (y == surface) {
            if (biome == SNOWY_PLAINS) return SNOW_BLOCK;
            return biome == STONY_PEAKS ? STONE : GRASS_BLOCK;
        } else if (y >= surface - 3) {
            return biome == STONY_PEAKS ? STONE : DIRT;
        }
        return y < seaLevel - 36 ? DEEPSLATE : STONE;
    }

    private static boolean isCave(long seed, int x, int y, int z) {
        int lowSeed = (int)seed;
        int highSeed = (int)(seed >>> 32);
        return noise3(lowSeed, highSeed, x, y, z, 48, 501) > 535
            && noise3(lowSeed, highSeed, x, y, z, 22, 502) > -160;
    }

    private static int amplitude(int q10, int amount) {
        return q10 * amount / Q;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int noise2(int seedLow, int seedHigh, int x, int z, int scale, int salt) {
        int gx = Math.floorDiv(x, scale);
        int gz = Math.floorDiv(z, scale);
        int sx = smoothQ10(Math.floorMod(x, scale), scale);
        int sz = smoothQ10(Math.floorMod(z, scale), scale);
        int a = lerpQ10(value(seedLow, seedHigh, gx, 0, gz, salt), value(seedLow, seedHigh, gx + 1, 0, gz, salt), sx);
        int b = lerpQ10(value(seedLow, seedHigh, gx, 0, gz + 1, salt), value(seedLow, seedHigh, gx + 1, 0, gz + 1, salt), sx);
        return lerpQ10(a, b, sz);
    }

    private static int noise3(int seedLow, int seedHigh, int x, int y, int z, int scale, int salt) {
        int gx = Math.floorDiv(x, scale);
        int gy = Math.floorDiv(y, scale);
        int gz = Math.floorDiv(z, scale);
        int sx = smoothQ10(Math.floorMod(x, scale), scale);
        int sy = smoothQ10(Math.floorMod(y, scale), scale);
        int sz = smoothQ10(Math.floorMod(z, scale), scale);
        int z00 = lerpQ10(value(seedLow, seedHigh, gx, gy, gz, salt), value(seedLow, seedHigh, gx + 1, gy, gz, salt), sx);
        int z10 = lerpQ10(value(seedLow, seedHigh, gx, gy + 1, gz, salt), value(seedLow, seedHigh, gx + 1, gy + 1, gz, salt), sx);
        int z01 = lerpQ10(value(seedLow, seedHigh, gx, gy, gz + 1, salt), value(seedLow, seedHigh, gx + 1, gy, gz + 1, salt), sx);
        int z11 = lerpQ10(value(seedLow, seedHigh, gx, gy + 1, gz + 1, salt), value(seedLow, seedHigh, gx + 1, gy + 1, gz + 1, salt), sx);
        return lerpQ10(lerpQ10(z00, z10, sy), lerpQ10(z01, z11, sy), sz);
    }

    private static int value(int seedLow, int seedHigh, int x, int y, int z, int salt) {
        return (hash(seedLow, seedHigh, x, y, z, salt) & 2047) - Q;
    }

    private static int hash(int seedLow, int seedHigh, int x, int y, int z, int salt) {
        int h = x * 0x9e3779b9;
        h ^= y * 0x85ebca6b;
        h ^= z * 0xc2b2ae35;
        h ^= seedLow;
        h ^= Integer.rotateLeft(seedHigh, 16);
        h ^= salt * 0x27d4eb2d;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static int smoothQ10(int fraction, int scale) {
        int t = fraction * Q / scale;
        return multiplyQ10(multiplyQ10(t, t), 3 * Q - 2 * t);
    }

    private static int multiplyQ10(int a, int b) {
        return a * b / Q;
    }

    private static int lerpQ10(int a, int b, int t) {
        return a + (b - a) * t / Q;
    }
}
