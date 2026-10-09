package org.gpur.compute;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Bounded numeric snapshot for the nearest-aquifer-cell ranking used by vanilla world generation.
 * The caller supplies block coordinates and the CPU-materialized aquifer-center grid; no world,
 * random-source, or block-state objects are retained or sent to the compute device.
 */
public final class VanillaAquiferBatch {
    public static final int WORKLOAD = 6;
    public static final int HEADER_WORDS = 8;
    public static final int CANDIDATE_COUNT = 12;
    public static final int RANK_COUNT = 4;
    public static final int MAX_QUERIES = 131_072;
    public static final int MAX_CENTER_CELLS = 65_536;

    private static final int MAX_HORIZONTAL_QUERY = 30_000_000;
    private static final int MAX_HORIZONTAL_CENTER = MAX_HORIZONTAL_QUERY + 32;
    private static final int CENTER_XZ_SPACING = 16;
    private static final int CENTER_Y_SPACING = 12;
    private static final int CENTER_XZ_RANGE = 10;
    private static final int CENTER_Y_RANGE = 9;

    private VanillaAquiferBatch() {}

    /**
     * Encodes queries and a complete aquifer-center grid. Query and center arrays are flat XYZ
     * tuples. Center tuples are ordered exactly like NoiseBasedAquifer#getIndex:
     * {@code ((y * gridSizeZ + z) * gridSizeX + x)} relative to the supplied grid origin.
     */
    public static int[] input(
        int minGridX,
        int minGridY,
        int minGridZ,
        int gridSizeX,
        int gridSizeY,
        int gridSizeZ,
        int[] queryBlockCoordinates,
        int[] centerBlockCoordinates
    ) {
        Objects.requireNonNull(queryBlockCoordinates, "queryBlockCoordinates");
        Objects.requireNonNull(centerBlockCoordinates, "centerBlockCoordinates");
        if (queryBlockCoordinates.length % 3 != 0) {
            throw new IllegalArgumentException("Aquifer queries must be XYZ tuples");
        }
        int queryCount = queryBlockCoordinates.length / 3;
        if (queryCount < 1 || queryCount > MAX_QUERIES) {
            throw new IllegalArgumentException("Aquifer query count is outside the bounded range");
        }
        long centerCount = centerCount(gridSizeX, gridSizeY, gridSizeZ);
        if (centerBlockCoordinates.length != centerCount * 3L) {
            throw new IllegalArgumentException("Aquifer center grid must contain one XYZ tuple per cell");
        }

        int[] words = new int[Math.toIntExact(HEADER_WORDS + (long)queryBlockCoordinates.length + centerBlockCoordinates.length)];
        words[0] = WORKLOAD;
        words[1] = queryCount;
        words[2] = minGridX;
        words[3] = minGridY;
        words[4] = minGridZ;
        words[5] = gridSizeX;
        words[6] = gridSizeY;
        words[7] = gridSizeZ;
        System.arraycopy(queryBlockCoordinates, 0, words, HEADER_WORDS, queryBlockCoordinates.length);
        System.arraycopy(centerBlockCoordinates, 0, words, HEADER_WORDS + queryBlockCoordinates.length, centerBlockCoordinates.length);
        validate(words);
        return words;
    }

    public static void validate(int[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < HEADER_WORDS || input[0] != WORKLOAD) {
            throw new IllegalArgumentException("Invalid vanilla aquifer header");
        }

        int queryCount = input[1];
        if (queryCount < 1 || queryCount > MAX_QUERIES) {
            throw new IllegalArgumentException("Aquifer query count is outside the bounded range");
        }

        int minGridX = input[2];
        int minGridY = input[3];
        int minGridZ = input[4];
        int gridSizeX = input[5];
        int gridSizeY = input[6];
        int gridSizeZ = input[7];
        long centers = centerCount(gridSizeX, gridSizeY, gridSizeZ);
        long expectedWords = HEADER_WORDS + (long)queryCount * 3L + centers * 3L;
        if (input.length != expectedWords) {
            throw new IllegalArgumentException("Invalid vanilla aquifer dimensions");
        }

        long minX = minGridX;
        long minY = minGridY;
        long minZ = minGridZ;
        long maxX = minX + gridSizeX;
        long maxY = minY + gridSizeY;
        long maxZ = minZ + gridSizeZ;
        if (maxX > Integer.MAX_VALUE || maxY > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Aquifer grid coordinates overflow");
        }

        int queryOffset = HEADER_WORDS;
        for (int query = 0; query < queryCount; query++) {
            int x = input[queryOffset + query * 3];
            int y = input[queryOffset + query * 3 + 1];
            int z = input[queryOffset + query * 3 + 2];
            validateQueryCoordinate(x, y, z);

            int anchorX = (x - 5) >> 4;
            int anchorY = Math.floorDiv(y + 1, CENTER_Y_SPACING);
            int anchorZ = (z - 5) >> 4;
            if (anchorX < minX || (long)anchorX + 1L >= maxX
                || (long)anchorY - 1L < minY || (long)anchorY + 1L >= maxY
                || anchorZ < minZ || (long)anchorZ + 1L >= maxZ) {
                throw new IllegalArgumentException("Aquifer query lacks its complete 2x3x2 center neighborhood");
            }
        }

        int centersOffset = queryOffset + queryCount * 3;
        for (int yIndex = 0; yIndex < gridSizeY; yIndex++) {
            long gridY = minY + yIndex;
            long baseY = gridY * CENTER_Y_SPACING;
            for (int zIndex = 0; zIndex < gridSizeZ; zIndex++) {
                long gridZ = minZ + zIndex;
                long baseZ = gridZ * CENTER_XZ_SPACING;
                for (int xIndex = 0; xIndex < gridSizeX; xIndex++) {
                    long gridX = minX + xIndex;
                    long baseX = gridX * CENTER_XZ_SPACING;
                    int centerIndex = (yIndex * gridSizeZ + zIndex) * gridSizeX + xIndex;
                    int offset = centersOffset + centerIndex * 3;
                    int x = input[offset];
                    int y = input[offset + 1];
                    int z = input[offset + 2];
                    if (x < baseX || x >= baseX + CENTER_XZ_RANGE
                        || y < baseY || y >= baseY + CENTER_Y_RANGE
                        || z < baseZ || z >= baseZ + CENTER_XZ_RANGE
                        || x < -MAX_HORIZONTAL_CENTER || x > MAX_HORIZONTAL_CENTER
                        || z < -MAX_HORIZONTAL_CENTER || z > MAX_HORIZONTAL_CENTER
                        || y < DimensionType.MIN_Y - 24 || y > DimensionType.MAX_Y + 24) {
                        throw new IllegalArgumentException("Aquifer center is outside its vanilla grid cell");
                    }
                }
            }
        }
    }

    public static int outputWords(int[] input) {
        validate(input);
        return Math.multiplyExact(input[1], RANK_COUNT);
    }

    /** Returns top-4 aquifer-grid cell indices for each query, in vanilla nearest-rank order. */
    public static int[] reference(int[] input) {
        validate(input);
        int queryCount = input[1];
        int minGridX = input[2];
        int minGridY = input[3];
        int minGridZ = input[4];
        int gridSizeX = input[5];
        int gridSizeZ = input[7];
        int queryOffset = HEADER_WORDS;
        int centersOffset = queryOffset + queryCount * 3;
        int[] result = new int[outputWordsUnchecked(queryCount)];

        for (int query = 0; query < queryCount; query++) {
            int queryOffsetXYZ = queryOffset + query * 3;
            int blockX = input[queryOffsetXYZ];
            int blockY = input[queryOffsetXYZ + 1];
            int blockZ = input[queryOffsetXYZ + 2];
            int anchorX = (blockX - 5) >> 4;
            int anchorY = Math.floorDiv(blockY + 1, CENTER_Y_SPACING);
            int anchorZ = (blockZ - 5) >> 4;

            int distance1 = Integer.MAX_VALUE;
            int distance2 = Integer.MAX_VALUE;
            int distance3 = Integer.MAX_VALUE;
            int distance4 = Integer.MAX_VALUE;
            int index1 = 0;
            int index2 = 0;
            int index3 = 0;
            int index4 = 0;

            for (int x1 = 0; x1 <= 1; x1++) {
                for (int y1 = -1; y1 <= 1; y1++) {
                    for (int z1 = 0; z1 <= 1; z1++) {
                        int gridX = anchorX + x1;
                        int gridY = anchorY + y1;
                        int gridZ = anchorZ + z1;
                        int cellIndex = ((gridY - minGridY) * gridSizeZ + gridZ - minGridZ) * gridSizeX + gridX - minGridX;
                        int centerOffset = centersOffset + cellIndex * 3;
                        int dx = input[centerOffset] - blockX;
                        int dy = input[centerOffset + 1] - blockY;
                        int dz = input[centerOffset + 2] - blockZ;
                        int distance = dx * dx + dy * dy + dz * dz;

                        // Keep NoiseBasedAquifer.computeSubstance's >= tie insertion and its
                        // x-then-y-then-z candidate visitation order exactly.
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

            int resultOffset = query * RANK_COUNT;
            result[resultOffset] = index1;
            result[resultOffset + 1] = index2;
            result[resultOffset + 2] = index3;
            result[resultOffset + 3] = index4;
        }
        return result;
    }

    public static int resultIndex(int[] result, int query, int rank) {
        Objects.requireNonNull(result, "result");
        if (result.length == 0 || result.length % RANK_COUNT != 0
            || query < 0 || query >= result.length / RANK_COUNT
            || rank < 0 || rank >= RANK_COUNT) {
            throw new IllegalArgumentException("Aquifer result coordinate is outside the result batch");
        }
        return result[query * RANK_COUNT + rank];
    }

    private static void validateQueryCoordinate(int x, int y, int z) {
        if (x < -MAX_HORIZONTAL_QUERY || x > MAX_HORIZONTAL_QUERY
            || z < -MAX_HORIZONTAL_QUERY || z > MAX_HORIZONTAL_QUERY
            || y < DimensionType.MIN_Y || y > DimensionType.MAX_Y) {
            throw new IllegalArgumentException("Aquifer query is outside vanilla world coordinates");
        }
    }

    private static long centerCount(int gridSizeX, int gridSizeY, int gridSizeZ) {
        if (gridSizeX < 1 || gridSizeY < 1 || gridSizeZ < 1) {
            throw new IllegalArgumentException("Aquifer grid dimensions must be positive");
        }
        long count = (long)gridSizeX * gridSizeY * gridSizeZ;
        if (count > MAX_CENTER_CELLS) {
            throw new IllegalArgumentException("Aquifer center grid exceeds the bounded cell count");
        }
        return count;
    }

    private static int outputWordsUnchecked(int queryCount) {
        return Math.multiplyExact(queryCount, RANK_COUNT);
    }
}
