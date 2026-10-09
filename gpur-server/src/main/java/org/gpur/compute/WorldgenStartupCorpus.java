package org.gpur.compute;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Small startup checks also compile the optional pipelines before a chunk can use them. */
final class WorldgenStartupCorpus {
    private WorldgenStartupCorpus() {}

    static List<int[]> workloads(boolean noise, boolean aquifer) {
        List<int[]> batches = new ArrayList<>();
        if (noise) {
            byte[] permutation = new byte[256];
            for (int i = 0; i < 256; i++) permutation[i] = (byte)i;
            Random random = new Random(0x4750757232L);
            for (int i = 255; i > 0; i--) {
                int j = random.nextInt(i + 1);
                byte value = permutation[i]; permutation[i] = permutation[j]; permutation[j] = value;
            }
            var improved = new VanillaNoiseBatch.ImprovedNoiseProfile(17.25, 42.5, 93.75, permutation);
            var perlin = new VanillaNoiseBatch.PerlinNoiseProfile(
                new VanillaNoiseBatch.ImprovedNoiseProfile[]{improved, null, improved},
                new double[]{1.0, 0.0, 0.5}, 0.25, 0.5);
            var normal = new VanillaNoiseBatch.NormalNoiseProfile(perlin, perlin, 0.75);
            List<VanillaNoiseBatch.NoiseSample> samples = new ArrayList<>();
            for (var profile : List.of(improved, perlin, normal)) {
                for (double x : new double[]{-29_999_984, -17.25, -0.0, 0.0, 1.125, 29_999_984}) {
                    samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, -64.0, x * 0.5, 0.0, 0.0));
                }
            }
            batches.add(VanillaNoiseBatch.input(samples));
        }
        if (aquifer) {
            int[] centers = new int[4 * 6 * 4 * 3];
            Random random = new Random(0x41717569666572L);
            int offset = 0;
            for (int y = -2; y < 4; y++) {
                for (int z = -2; z < 2; z++) {
                    for (int x = -2; x < 2; x++) {
                        centers[offset++] = x * 16 + random.nextInt(10);
                        centers[offset++] = y * 12 + random.nextInt(9);
                        centers[offset++] = z * 16 + random.nextInt(10);
                    }
                }
            }
            batches.add(VanillaAquiferBatch.input(-2, -2, -2, 4, 6, 4,
                new int[]{-16, -12, -16, 0, 0, 0, 16, 24, 16, -1, -1, -1}, centers));
        }
        return List.copyOf(batches);
    }
}
