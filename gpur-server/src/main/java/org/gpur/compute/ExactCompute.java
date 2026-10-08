package org.gpur.compute;

/** Wire format and CPU reference. No world, RNG, or plugin state is accessed here. */
public final class ExactCompute {
    public static final int SECTION_WORDS = 4096 + 5832;

    private ExactCompute() {}

    public static int[] distances(double x, double y, double z, double[] positions) {
        if (positions.length % 3 != 0 || positions.length > (Integer.MAX_VALUE - 8) / 2) throw new IllegalArgumentException("xyz tuples required");
        int[] input = new int[8 + positions.length * 2];
        input[0] = 1;
        input[1] = positions.length / 3;
        putDouble(input, 2, x);
        putDouble(input, 4, y);
        putDouble(input, 6, z);
        for (int i = 0; i < positions.length; i++) putDouble(input, 8 + i * 2, positions[i]);
        return input;
    }

    public static double getDouble(int[] words, int offset) {
        return Double.longBitsToDouble(Integer.toUnsignedLong(words[offset]) | ((long)words[offset + 1] << 32));
    }

    private static void putDouble(int[] words, int offset, double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int)bits;
        words[offset + 1] = (int)(bits >>> 32);
    }

    public static void validate(int[] input) {
        if (input.length < 2 || input[1] < 0) throw new IllegalArgumentException("Invalid compute header");
        long count = input[1];
        if (input[0] == 1 && input.length == 8L + count * 6) return;
        if (input[0] == 2 && count % 4096 == 0 && input.length == 2L + count / 4096 * SECTION_WORDS) return;
        if (input[0] == 3) {
            org.gpur.terrain.TerrainRules.validate(input);
            return;
        }
        if (input[0] == VanillaTerrainInterpolation.WORKLOAD) {
            VanillaTerrainInterpolation.validate(input);
            return;
        }
        throw new IllegalArgumentException("Invalid GPur workload dimensions");
    }

    public static int outputWords(int[] input) {
        validate(input);
        if (input[0] == 3) return org.gpur.terrain.TerrainRules.outputWords(input);
        if (input[0] == VanillaTerrainInterpolation.WORKLOAD) return VanillaTerrainInterpolation.outputWords(input);
        return Math.multiplyExact(input[1], input[0] == 1 ? 2 : 1);
    }

    public static int[] reference(int[] input) {
        validate(input);
        if (input[0] == 3) return org.gpur.terrain.TerrainRules.reference(input);
        if (input[0] == VanillaTerrainInterpolation.WORKLOAD) return VanillaTerrainInterpolation.reference(input);
        if (input.length < 2 || input[1] < 0) throw new IllegalArgumentException("Invalid compute header");
        int count = input[1];
        if (input[0] == 1) {
            if (input.length != 8L + (long)count * 6) throw new IllegalArgumentException("Invalid distance dimensions");
            int[] result = new int[count * 2];
            double x = getDouble(input, 2), y = getDouble(input, 4), z = getDouble(input, 6);
            for (int i = 0; i < count; i++) {
                double dx = x - getDouble(input, 8 + i * 6);
                double dy = y - getDouble(input, 10 + i * 6);
                double dz = z - getDouble(input, 12 + i * 6);
                putDouble(result, i * 2, dx * dx + dy * dy + dz * dz);
            }
            return result;
        }
        if (input[0] != 2 || count % 4096 != 0 || input.length != 2L + (long)(count / 4096) * SECTION_WORDS) {
            throw new IllegalArgumentException("Invalid Anti-Xray dimensions");
        }
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int block = i % 4096, offset = 2 + i / 4096 * SECTION_WORDS;
            int center = offset + 4096 + ((block >> 8) + 1) * 324
                + (((block >> 4) & 15) + 1) * 18 + (block & 15) + 1;
            result[i] = (input[offset + block] & 3) == 3
                && input[center - 1] == 0 && input[center + 1] == 0
                && input[center - 18] == 0 && input[center + 18] == 0
                && input[center - 324] == 0 && input[center + 324] == 0 ? 1 : 0;
        }
        return result;
    }

    public static boolean equal(int[] expected, int[] actual, int workload) {
        if (actual == null || expected.length != actual.length) return false;
        if (workload != 1) return java.util.Arrays.equals(expected, actual);
        for (int i = 0; i < expected.length; i += 2) {
            double a = getDouble(expected, i), b = getDouble(actual, i);
            if (Double.isNaN(a) && Double.isNaN(b)) continue;
            if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) return false;
        }
        return true;
    }
}
