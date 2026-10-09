package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.Test;

class VanillaNoiseBatchTest {
    @Test
    void improvedNoiseReferenceMatchesVanillaIncludingVerticalFudge() {
        ImprovedNoise noise = new ImprovedNoise(RandomSource.create(0x17e11L));
        VanillaNoiseBatch.ImprovedNoiseProfile profile = snapshot(noise);
        VanillaNoiseBatch.NoiseSample[] samples = {
            new VanillaNoiseBatch.NoiseSample(profile, -17.25, -0.125, 31.875, 0.0, 0.0),
            new VanillaNoiseBatch.NoiseSample(profile, 0.0, 0.625, 0.0, 0.125, 0.1875),
            new VanillaNoiseBatch.NoiseSample(profile, 255.9375, -63.8125, -0.0625, 0.25, 0.0625),
            new VanillaNoiseBatch.NoiseSample(profile, 16_777_211.5, 0.4375, -16_777_203.25, 0.5, -0.25)
        };
        int[] input = VanillaNoiseBatch.input(samples);
        int[] output = VanillaNoiseBatch.reference(input);
        double[][] coordinates = {
            {-17.25, -0.125, 31.875, 0.0, 0.0},
            {0.0, 0.625, 0.0, 0.125, 0.1875},
            {255.9375, -63.8125, -0.0625, 0.25, 0.0625},
            {16_777_211.5, 0.4375, -16_777_203.25, 0.5, -0.25}
        };
        for (int i = 0; i < samples.length; i++) {
            double[] c = coordinates[i];
            assertRawEquals(noise.noise(c[0], c[1], c[2], c[3], c[4]), VanillaNoiseBatch.outputValue(output, i));
        }
    }

    @Test
    void perlinNoiseReferencePreservesSequentialOctaveAccumulation() {
        PerlinNoise noise = PerlinNoise.create(RandomSource.create(0x51a9L), -3, 0.5, 1.0, 0.25, 0.0, 0.75);
        VanillaNoiseBatch.PerlinNoiseProfile profile = snapshot(noise);
        VanillaNoiseBatch.NoiseSample[] samples = {
            new VanillaNoiseBatch.NoiseSample(profile, -1234.125, -91.75, 847.5, 0.0, 0.0),
            new VanillaNoiseBatch.NoiseSample(profile, 17.375, 0.6875, -88.125, 0.125, 0.25),
            new VanillaNoiseBatch.NoiseSample(profile, 30_000_000.125, 255.5, -29_999_999.75, 0.5, 0.125)
        };
        int[] output = VanillaNoiseBatch.reference(VanillaNoiseBatch.input(samples));
        for (int i = 0; i < samples.length; i++) {
            VanillaNoiseBatch.NoiseSample sample = samples[i];
            assertRawEquals(
                noise.getValue(sample.x(), sample.y(), sample.z(), sample.yScale(), sample.yFudge()),
                VanillaNoiseBatch.outputValue(output, i)
            );
        }
    }

    @Test
    void normalNoiseReferenceScalesOnlyTheSecondPerlinInput() {
        NormalNoise noise = NormalNoise.create(RandomSource.create(0x6f2aL), -2, 0.75, 1.0, 0.375, 0.5);
        VanillaNoiseBatch.NormalNoiseProfile profile = snapshot(noise);
        VanillaNoiseBatch.NoiseSample[] samples = {
            new VanillaNoiseBatch.NoiseSample(profile, -843.625, -72.125, 918.4375, 0.0, 0.0),
            new VanillaNoiseBatch.NoiseSample(profile, 30_000_000.125, 256.0, -30_000_000.25, 0.0, 0.0)
        };
        int[] output = VanillaNoiseBatch.reference(VanillaNoiseBatch.input(samples));
        for (int i = 0; i < samples.length; i++) {
            VanillaNoiseBatch.NoiseSample sample = samples[i];
            assertRawEquals(noise.getValue(sample.x(), sample.y(), sample.z()), VanillaNoiseBatch.outputValue(output, i));
        }
    }

    @Test
    void blendedNoiseReferenceMatchesVanillaAtWorldCoordinates() {
        BlendedNoise noise = new BlendedNoise(RandomSource.create(0x2cbbL), 1.0, 1.0, 8.55515, 4.277575, 2.0);
        VanillaNoiseBatch.BlendedNoiseProfile profile = snapshot(noise);
        int[][] positions = {{-187, -53, 91}, {0, 64, 0}, {30_000_000, 255, -30_000_000}};
        VanillaNoiseBatch.NoiseSample[] samples = new VanillaNoiseBatch.NoiseSample[positions.length];
        double[] expected = new double[positions.length];
        for (int i = 0; i < positions.length; i++) {
            int[] p = positions[i];
            DensityFunction.FunctionContext context = context(p[0], p[1], p[2]);
            samples[i] = new VanillaNoiseBatch.NoiseSample(profile, p[0], p[1], p[2], 0.0, 0.0);
            expected[i] = noise.compute(context);
        }
        int[] output = VanillaNoiseBatch.reference(VanillaNoiseBatch.input(samples));
        for (int i = 0; i < positions.length; i++) assertRawEquals(expected[i], VanillaNoiseBatch.outputValue(output, i));
    }

    @Test
    void malformedProfileOffsetsAndPermutationTablesAreRejected() {
        ImprovedNoise noise = new ImprovedNoise(RandomSource.create(0x881L));
        int[] input = VanillaNoiseBatch.input(new VanillaNoiseBatch.NoiseSample(snapshot(noise), 1.0, 2.0, 3.0, 0.0, 0.0));
        int rootOffset = input[input[4]];

        int[] badPermutation = input.clone();
        Arrays.fill(badPermutation, rootOffset + 8, rootOffset + 72, 0);
        assertThrows(IllegalArgumentException.class, () -> VanillaNoiseBatch.validate(badPermutation));

        int[] badOffset = input.clone();
        badOffset[input[4]] = rootOffset + 1;
        assertThrows(IllegalArgumentException.class, () -> VanillaNoiseBatch.validate(badOffset));

        int[] badSampleCount = input.clone();
        badSampleCount[1] = 0;
        assertThrows(IllegalArgumentException.class, () -> VanillaNoiseBatch.validate(badSampleCount));
    }

    @Test
    void sampleAndSnapshotConstructorsRejectMalformedState() {
        byte[] duplicatePermutation = new byte[256];
        for (int i = 0; i < duplicatePermutation.length; i++) duplicatePermutation[i] = (byte)i;
        duplicatePermutation[255] = duplicatePermutation[0];
        assertThrows(
            IllegalArgumentException.class,
            () -> new VanillaNoiseBatch.ImprovedNoiseProfile(0.0, 0.0, 0.0, duplicatePermutation)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> VanillaNoiseBatch.input(new VanillaNoiseBatch.NoiseSample(
                snapshot(new ImprovedNoise(RandomSource.create(3L))), Double.NaN, 0.0, 0.0, 0.0, 0.0
            ))
        );
    }

    private static DensityFunction.FunctionContext context(int x, int y, int z) {
        return new DensityFunction.FunctionContext() {
            @Override public int blockX() { return x; }
            @Override public int blockY() { return y; }
            @Override public int blockZ() { return z; }
        };
    }

    private static VanillaNoiseBatch.ImprovedNoiseProfile snapshot(ImprovedNoise noise) {
        return new VanillaNoiseBatch.ImprovedNoiseProfile(
            noise.xo,
            noise.yo,
            noise.zo,
            (byte[])field(noise, "p")
        );
    }

    private static VanillaNoiseBatch.PerlinNoiseProfile snapshot(PerlinNoise noise) {
        ImprovedNoise[] levels = (ImprovedNoise[])field(noise, "noiseLevels");
        Object amplitudeList = field(noise, "amplitudes");
        double[] amplitudes = new double[levels.length];
        for (int i = 0; i < levels.length; i++) amplitudes[i] = (double)invokeDouble(amplitudeList, "getDouble", i);
        VanillaNoiseBatch.ImprovedNoiseProfile[] profiles = new VanillaNoiseBatch.ImprovedNoiseProfile[levels.length];
        for (int i = 0; i < levels.length; i++) if (levels[i] != null) profiles[i] = snapshot(levels[i]);
        return new VanillaNoiseBatch.PerlinNoiseProfile(
            profiles,
            amplitudes,
            (double)field(noise, "lowestFreqInputFactor"),
            (double)field(noise, "lowestFreqValueFactor")
        );
    }

    private static VanillaNoiseBatch.NormalNoiseProfile snapshot(NormalNoise noise) {
        return new VanillaNoiseBatch.NormalNoiseProfile(
            snapshot((PerlinNoise)field(noise, "first")),
            snapshot((PerlinNoise)field(noise, "second")),
            (double)field(noise, "valueFactor")
        );
    }

    private static VanillaNoiseBatch.BlendedNoiseProfile snapshot(BlendedNoise noise) {
        return new VanillaNoiseBatch.BlendedNoiseProfile(
            snapshot((PerlinNoise)field(noise, "minLimitNoise")),
            snapshot((PerlinNoise)field(noise, "maxLimitNoise")),
            snapshot((PerlinNoise)field(noise, "mainNoise")),
            (double)field(noise, "xzMultiplier"),
            (double)field(noise, "yMultiplier"),
            (double)field(noise, "xzFactor"),
            (double)field(noise, "yFactor"),
            (double)field(noise, "smearScaleMultiplier")
        );
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not snapshot vanilla noise field " + name, exception);
        }
    }

    private static Object invokeDouble(Object owner, String method, int index) {
        try {
            return owner.getClass().getMethod(method, int.class).invoke(owner, index);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not read vanilla octave amplitude", exception);
        }
    }

    private static void assertRawEquals(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
