package org.gpur.compute;

import java.util.Objects;
import net.minecraft.util.Mth;

/**
 * Bounded wire format and CPU reference for vanilla NoiseChunk interpolation.
 * The density graph itself is intentionally sampled by Minecraft on the CPU.
 */
public final class VanillaTerrainInterpolation {
    public static final int WORKLOAD = 4;
    public static final int HEADER_WORDS = 6;
    public static final int MAX_INTERPOLATORS = 16;
    public static final int MAX_HEIGHT = 512;
    public static final int MAX_DOUBLES = 1_048_576;
    public static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    public static final double MAX_CORNER_MAGNITUDE = 1_000_000.0;

    private static final int MAX_OUTPUT_DOUBLES = MAX_OUTPUT_BYTES / Double.BYTES;

    private VanillaTerrainInterpolation() {}

    /**
     * Encodes density slices as [interpolator][slice0 then slice1][z boundary][y boundary].
     * Each source slice has shape [16 / cellWidth + 1][cellCountY + 1].
     */
    public static int[] input(
        int cellWidth,
        int cellHeight,
        int cellCountY,
        double[][][] slice0,
        double[][][] slice1
    ) {
        Dimensions dimensions = dimensions(cellWidth, cellHeight, cellCountY);
        Objects.requireNonNull(slice0, "slice0");
        Objects.requireNonNull(slice1, "slice1");
        if (slice0.length < 1 || slice0.length > MAX_INTERPOLATORS || slice1.length != slice0.length) {
            throw new IllegalArgumentException("Interpolation requires 1..16 paired interpolators");
        }

        long doubleCount = (long)slice0.length * 2L * dimensions.zBoundaries * dimensions.yBoundaries;
        long invocationCount = invocationCount(cellWidth, cellHeight, cellCountY, slice0.length);
        requireBoundedCounts(doubleCount, invocationCount);
        for (int interpolator = 0; interpolator < slice0.length; interpolator++) {
            validateSlice(slice0[interpolator], dimensions);
            validateSlice(slice1[interpolator], dimensions);
        }
        int[] words = new int[Math.toIntExact(HEADER_WORDS + doubleCount * 2L)];
        words[0] = WORKLOAD;
        words[1] = Math.toIntExact(invocationCount);
        words[2] = cellWidth;
        words[3] = cellHeight;
        words[4] = cellCountY;
        words[5] = slice0.length;

        int offset = HEADER_WORDS;
        for (int interpolator = 0; interpolator < slice0.length; interpolator++) {
            copySlice(words, offset, slice0[interpolator], dimensions);
            offset += dimensions.wordsPerSlice();
            copySlice(words, offset, slice1[interpolator], dimensions);
            offset += dimensions.wordsPerSlice();
        }
        return words;
    }

    private static void copySlice(int[] words, int offset, double[][] slice, Dimensions dimensions) {
        for (int z = 0; z < dimensions.zBoundaries; z++) {
            for (int y = 0; y < dimensions.yBoundaries; y++) {
                putDoubleBits(words, offset + (z * dimensions.yBoundaries + y) * 2, slice[z][y]);
            }
        }
    }

    private static void validateSlice(double[][] slice, Dimensions dimensions) {
        Objects.requireNonNull(slice, "slice");
        if (slice.length != dimensions.zBoundaries) {
            throw new IllegalArgumentException("Incorrect interpolation slice Z boundary count");
        }
        for (double[] row : slice) {
            Objects.requireNonNull(row, "slice row");
            if (row.length != dimensions.yBoundaries) {
                throw new IllegalArgumentException("Incorrect interpolation slice Y boundary count");
            }
            for (double value : row) {
                if (!Double.isFinite(value) || Math.abs(value) > MAX_CORNER_MAGNITUDE) {
                    throw new IllegalArgumentException("Interpolation corner is non-finite or out of range");
                }
            }
        }
    }

    public static void validate(int[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < HEADER_WORDS || input[0] != WORKLOAD) {
            throw new IllegalArgumentException("Invalid vanilla interpolation header");
        }
        Dimensions dimensions = dimensions(input[2], input[3], input[4]);
        int interpolators = input[5];
        if (interpolators < 1 || interpolators > MAX_INTERPOLATORS) {
            throw new IllegalArgumentException("Interpolation requires 1..16 paired interpolators");
        }
        long doubleCount = (long)interpolators * 2L * dimensions.zBoundaries * dimensions.yBoundaries;
        long invocationCount = invocationCount(input[2], input[3], input[4], interpolators);
        requireBoundedCounts(doubleCount, invocationCount);
        if (input[1] != invocationCount || input.length != HEADER_WORDS + doubleCount * 2L) {
            throw new IllegalArgumentException("Invalid vanilla interpolation dimensions");
        }
        for (int offset = HEADER_WORDS; offset < input.length; offset += 2) {
            double value = getDouble(input, offset);
            if (!Double.isFinite(value) || Math.abs(value) > MAX_CORNER_MAGNITUDE) {
                throw new IllegalArgumentException("Interpolation corner is non-finite or out of range");
            }
        }
    }

    public static int outputWords(int[] input) {
        validate(input);
        return Math.multiplyExact(input[1], 2);
    }

    /** Returns results as [interpolator][normal then cache-fill][ascending Y][local X][Z across 16]. */
    public static int[] reference(int[] input) {
        validate(input);
        int cellWidth = input[2];
        int cellHeight = input[3];
        int cellCountY = input[4];
        int interpolatorCount = input[5];
        int totalHeight = Math.multiplyExact(cellHeight, cellCountY);
        int[] result = new int[outputWordsUnchecked(input[1])];
        int invocation = 0;

        for (int interpolator = 0; interpolator < interpolatorCount; interpolator++) {
            for (int mode = 0; mode < 2; mode++) {
                boolean fillingCell = mode == 1;
                for (int yAscending = 0; yAscending < totalHeight; yAscending++) {
                    int cellY = yAscending / cellHeight;
                    int localY = yAscending % cellHeight;
                    double alphaY = (double)localY / cellHeight;
                    for (int localX = 0; localX < cellWidth; localX++) {
                        double alphaX = (double)localX / cellWidth;
                        for (int zAcross16 = 0; zAcross16 < 16; zAcross16++) {
                            int cellZ = zAcross16 / cellWidth;
                            int localZ = zAcross16 % cellWidth;
                            double alphaZ = (double)localZ / cellWidth;
                            double interpolated = interpolate(
                                input,
                                cellCountY,
                                interpolator,
                                cellY,
                                cellZ,
                                alphaX,
                                alphaY,
                                alphaZ,
                                fillingCell
                            );
                            putDoubleBits(result, invocation * 2, interpolated);
                            invocation++;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Reads one output value. cellY/cellZ select a vanilla density cell; local coordinates
     * select the block within that cell. localZ is within the cell, not chunk-wide.
     */
    public static double value(
        int[] result,
        int cellWidth,
        int cellHeight,
        int cellCountY,
        int interpolator,
        int cellY,
        int cellZ,
        int localY,
        int localX,
        int localZ,
        boolean fillingCell
    ) {
        Objects.requireNonNull(result, "result");
        Dimensions dimensions = dimensions(cellWidth, cellHeight, cellCountY);
        int cellCountZ = 16 / cellWidth;
        long wordsPerInterpolator = 4L * dimensions.totalHeight * cellWidth * 16L;
        if (result.length == 0 || result.length > (long)MAX_OUTPUT_DOUBLES * 2L
            || result.length % wordsPerInterpolator != 0) {
            throw new IllegalArgumentException("Incorrect interpolation result dimensions");
        }
        int interpolatorCount = Math.toIntExact(result.length / wordsPerInterpolator);
        if (interpolatorCount < 1 || interpolatorCount > MAX_INTERPOLATORS
            || interpolator < 0 || interpolator >= interpolatorCount
            || cellY < 0 || cellY >= cellCountY || cellZ < 0 || cellZ >= cellCountZ
            || localY < 0 || localY >= cellHeight || localX < 0 || localX >= cellWidth
            || localZ < 0 || localZ >= cellWidth) {
            throw new IllegalArgumentException("Interpolation result coordinate is out of range");
        }
        int yAscending = cellY * cellHeight + localY;
        int zAcross16 = cellZ * cellWidth + localZ;
        int mode = fillingCell ? 1 : 0;
        int invocation = outputIndex(cellWidth, cellHeight, cellCountY, interpolator, mode, yAscending, localX, zAcross16);
        return getDouble(result, invocation * 2);
    }

    private static double interpolate(
        int[] input,
        int cellCountY,
        int interpolator,
        int cellY,
        int cellZ,
        double alphaX,
        double alphaY,
        double alphaZ,
        boolean fillingCell
    ) {
        int cellWidth = input[2];
        int yStride = cellCountY + 1;
        int zStride = 16 / cellWidth + 1;
        double n000 = corner(input, interpolator, 0, zStride, yStride, cellZ, cellY);
        double n100 = corner(input, interpolator, 1, zStride, yStride, cellZ, cellY);
        double n010 = corner(input, interpolator, 0, zStride, yStride, cellZ, cellY + 1);
        double n110 = corner(input, interpolator, 1, zStride, yStride, cellZ, cellY + 1);
        double n001 = corner(input, interpolator, 0, zStride, yStride, cellZ + 1, cellY);
        double n101 = corner(input, interpolator, 1, zStride, yStride, cellZ + 1, cellY);
        double n011 = corner(input, interpolator, 0, zStride, yStride, cellZ + 1, cellY + 1);
        double n111 = corner(input, interpolator, 1, zStride, yStride, cellZ + 1, cellY + 1);
        if (fillingCell) {
            // This is deliberately the exact Mth.lerp3 -> lerp2 -> lerp order.
            return Mth.lerp3(alphaX, alphaY, alphaZ, n000, n100, n010, n110, n001, n101, n011, n111);
        }

        // NoiseInterpolator.updateForY, then updateForX, then updateForZ.
        double valueXZ00 = Mth.lerp(alphaY, n000, n010);
        double valueXZ10 = Mth.lerp(alphaY, n100, n110);
        double valueXZ01 = Mth.lerp(alphaY, n001, n011);
        double valueXZ11 = Mth.lerp(alphaY, n101, n111);
        double valueZ0 = Mth.lerp(alphaX, valueXZ00, valueXZ10);
        double valueZ1 = Mth.lerp(alphaX, valueXZ01, valueXZ11);
        return Mth.lerp(alphaZ, valueZ0, valueZ1);
    }

    private static double corner(int[] input, int interpolator, int slice, int zStride, int yStride, int z, int y) {
        int offset = HEADER_WORDS + ((interpolator * 2 + slice) * zStride * yStride + z * yStride + y) * 2;
        return getDouble(input, offset);
    }

    private static int outputIndex(
        int cellWidth, int cellHeight, int cellCountY, int interpolator, int mode, int yAscending, int localX, int zAcross16
    ) {
        int totalHeight = Math.multiplyExact(cellHeight, cellCountY);
        return Math.toIntExact(((((long)interpolator * 2L + mode) * totalHeight + yAscending) * cellWidth + localX) * 16L + zAcross16);
    }

    private static Dimensions dimensions(int cellWidth, int cellHeight, int cellCountY) {
        if (cellWidth < 1 || cellWidth > 16 || 16 % cellWidth != 0) {
            throw new IllegalArgumentException("cellWidth must divide 16");
        }
        if (cellHeight < 1 || cellHeight > 32 || (cellHeight & (cellHeight - 1)) != 0) {
            throw new IllegalArgumentException("cellHeight must be a power of two no greater than 32");
        }
        if (cellCountY < 1 || (long)cellHeight * cellCountY > MAX_HEIGHT) {
            throw new IllegalArgumentException("Vertical interpolation extent must be 1..512 blocks");
        }
        return new Dimensions(16 / cellWidth + 1, cellCountY + 1, cellHeight * cellCountY);
    }

    private static long invocationCount(int cellWidth, int cellHeight, int cellCountY, int interpolators) {
        return (long)interpolators * 2L * cellHeight * cellCountY * cellWidth * 16L;
    }

    private static void requireBoundedCounts(long inputDoubles, long invocations) {
        if (inputDoubles < 1 || inputDoubles > MAX_DOUBLES
            || invocations < 1 || invocations > MAX_OUTPUT_DOUBLES) {
            throw new IllegalArgumentException("Interpolation slab exceeds bounded input or output size");
        }
    }

    private static int outputWordsUnchecked(int invocations) {
        return Math.multiplyExact(invocations, 2);
    }

    private static void putDouble(int[] words, int offset, double value) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_CORNER_MAGNITUDE) {
            throw new IllegalArgumentException("Interpolation corner is non-finite or out of range");
        }
        putDoubleBits(words, offset, value);
    }

    private static void putDoubleBits(int[] words, int offset, double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int)bits;
        words[offset + 1] = (int)(bits >>> 32);
    }

    private static double getDouble(int[] words, int offset) {
        return Double.longBitsToDouble(Integer.toUnsignedLong(words[offset]) | ((long)words[offset + 1] << 32));
    }

    private record Dimensions(int zBoundaries, int yBoundaries, int totalHeight) {
        private int wordsPerSlice() {
            return Math.multiplyExact(Math.multiplyExact(this.zBoundaries, this.yBoundaries), 2);
        }
    }
}
