package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.gpur.GPurConfig;
import org.gpur.compute.VulkanDevice.DeviceMetrics;
import org.gpur.compute.VulkanDevice.Info;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in physical Vulkan parity checks for exact vanilla noise and aquifer ranking. */
@EnabledIfSystemProperty(named = "gpur.gpu-tests", matches = "true")
final class VanillaWorldgenKernelHardwareTest {
    private static final int MIXED_ROUNDS = 4;
    private static final long FUTURE_TIMEOUT_SECONDS = 60;

    @Test
    void everyPhysicalUuidPassesExactNoiseAndLiteralAquiferKernelsUnderMixedConcurrency() throws Exception {
        List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        assertFalse(devices.isEmpty(), "Hardware test requires at least one Vulkan FP64 device");
        assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count(), "Device UUIDs must be unique");
        List<KernelCase> cases = List.of(noiseCase(), aquiferFirstTieCase(), aquiferNegativeBoundaryCase());

        try (ConfigSnapshot config = ConfigSnapshot.capture()) {
            configureHardwareTest();
            for (Info info : devices) verifyDevice(info, cases);
        }
    }

    private static void verifyDevice(Info info, List<KernelCase> cases) throws Exception {
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name() + " must open by physical UUID " + info.uuid());
            DeviceMetrics before = device.metrics();
            int completedJobs = 0;

            // Require a native result for each workload/profile family; this path has no CPU fallback.
            for (KernelCase kernelCase : cases) {
                int[] actual = device.computeAsync(
                    kernelCase.input(), ExactCompute.outputWords(kernelCase.input()), kernelCase.input()[1]
                ).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertKernelResult(info, kernelCase, actual, "single");
                completedJobs++;
            }

            // Mix workload 5 and workload 6 submissions while the Vulkan worker is active.
            List<CompletableFuture<int[]>> pending = new ArrayList<>(cases.size() * MIXED_ROUNDS);
            List<KernelCase> expectedCases = new ArrayList<>(cases.size() * MIXED_ROUNDS);
            for (int round = 0; round < MIXED_ROUNDS; round++) {
                for (KernelCase kernelCase : cases) {
                    pending.add(device.computeAsync(
                        kernelCase.input(), ExactCompute.outputWords(kernelCase.input()), kernelCase.input()[1]
                    ));
                    expectedCases.add(kernelCase);
                }
            }
            for (int i = 0; i < pending.size(); i++) {
                assertKernelResult(info, expectedCases.get(i), pending.get(i).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "mixed-" + i);
                completedJobs++;
            }

            DeviceMetrics after = device.metrics();
            assertEquals(completedJobs, after.submittedJobs() - before.submittedJobs(), info.name() + " native submissions");
            assertEquals(completedJobs, after.completedJobs() - before.completedJobs(), info.name() + " native completions");
            assertEquals(0, after.rejectedJobs() - before.rejectedJobs(), info.name() + " rejected jobs");
            assertEquals(0, after.timedOutJobs() - before.timedOutJobs(), info.name() + " timed out jobs");
            assertEquals(0, after.failedJobs() - before.failedJobs(), info.name() + " failed jobs");
            assertTrue(device.available(), info.name() + " remains available after exact parity");
            System.out.println("GPur worldgen Vulkan " + info.name() + " uuid=" + info.uuid()
                + " workload5Profiles=4 workload6LiteralCases=2 nativeJobs=" + completedJobs
                + " nativeCompleted=" + (after.completedJobs() - before.completedJobs())
                + " nullResults=0 rejected=0 timedOut=0 failed=0");
        }
    }

    private static void assertKernelResult(Info info, KernelCase kernelCase, int[] actual, String label) {
        assertNotNull(actual, info.name() + " " + label + " " + kernelCase.name() + " must return a native result");
        assertArrayEquals(kernelCase.expected(), actual, info.name() + " " + label + " " + kernelCase.name() + " raw output");
    }

    private static KernelCase noiseCase() {
        List<VanillaNoiseBatch.NoiseSample> samples = new ArrayList<>();
        List<Double> expected = new ArrayList<>();

        ImprovedNoise improved = new ImprovedNoise(RandomSource.create(0x17e11L));
        VanillaNoiseBatch.ImprovedNoiseProfile improvedProfile = snapshot(improved);
        addImproved(samples, expected, improvedProfile, improved, -17.25, -0.125, 31.875, 0.0, 0.0);
        addImproved(samples, expected, improvedProfile, improved, 0.0, 0.625, 0.0, 0.125, 0.1875);
        addImproved(samples, expected, improvedProfile, improved, -30_000_000.25, 31.5, 29_999_999.875, 0.25, 0.0625);
        addImproved(samples, expected, improvedProfile, improved, 255.9375, -63.8125, -0.0625, 0.5, -0.25);

        PerlinNoise perlin = PerlinNoise.create(RandomSource.create(0x51a9L), -3, 0.5, 1.0, 0.25, 0.0, 0.75);
        VanillaNoiseBatch.PerlinNoiseProfile perlinProfile = snapshot(perlin);
        addPerlin(samples, expected, perlinProfile, perlin, -1234.125, -91.75, 847.5, 0.0, 0.0);
        addPerlin(samples, expected, perlinProfile, perlin, 17.375, 0.6875, -88.125, 0.125, 0.25);
        addPerlin(samples, expected, perlinProfile, perlin, 30_000_000.125, 255.5, -29_999_999.75, 0.5, 0.125);

        NormalNoise normal = NormalNoise.create(RandomSource.create(0x6f2aL), -2, 0.75, 1.0, 0.375, 0.5);
        VanillaNoiseBatch.NormalNoiseProfile normalProfile = snapshot(normal);
        addNormal(samples, expected, normalProfile, normal, -843.625, -72.125, 918.4375);
        addNormal(samples, expected, normalProfile, normal, 30_000_000.125, 256.0, -30_000_000.25);

        BlendedNoise blended = new BlendedNoise(RandomSource.create(0x2cbbL), 1.0, 1.0, 8.55515, 4.277575, 2.0);
        VanillaNoiseBatch.BlendedNoiseProfile blendedProfile = snapshot(blended);
        addBlended(samples, expected, blendedProfile, blended, -187, -53, 91);
        addBlended(samples, expected, blendedProfile, blended, 0, 64, 0);
        addBlended(samples, expected, blendedProfile, blended, 30_000_000, 255, -30_000_000);

        return new KernelCase("noise-4-profiles", VanillaNoiseBatch.input(samples), doubleWords(expected));
    }

    private static void addImproved(
        List<VanillaNoiseBatch.NoiseSample> samples,
        List<Double> expected,
        VanillaNoiseBatch.ImprovedNoiseProfile profile,
        ImprovedNoise noise,
        double x,
        double y,
        double z,
        double yScale,
        double yFudge
    ) {
        samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, y, z, yScale, yFudge));
        expected.add(noise.noise(x, y, z, yScale, yFudge));
    }

    private static void addPerlin(
        List<VanillaNoiseBatch.NoiseSample> samples,
        List<Double> expected,
        VanillaNoiseBatch.PerlinNoiseProfile profile,
        PerlinNoise noise,
        double x,
        double y,
        double z,
        double yScale,
        double yFudge
    ) {
        samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, y, z, yScale, yFudge));
        expected.add(noise.getValue(x, y, z, yScale, yFudge));
    }

    private static void addNormal(
        List<VanillaNoiseBatch.NoiseSample> samples,
        List<Double> expected,
        VanillaNoiseBatch.NormalNoiseProfile profile,
        NormalNoise noise,
        double x,
        double y,
        double z
    ) {
        samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, y, z, 0.0, 0.0));
        expected.add(noise.getValue(x, y, z));
    }

    private static void addBlended(
        List<VanillaNoiseBatch.NoiseSample> samples,
        List<Double> expected,
        VanillaNoiseBatch.BlendedNoiseProfile profile,
        BlendedNoise noise,
        int x,
        int y,
        int z
    ) {
        samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, y, z, 0.0, 0.0));
        expected.add(noise.compute(context(x, y, z)));
    }

    private static KernelCase aquiferFirstTieCase() {
        int[] centers = {
            -24, -32, -24, -12, -32, -24, -24, -32, -12, -12, -32, -12,
            -24, -20, -24, -12, -20, -24, -24, -20, -12, -12, -20, -12,
            -24, -8, -24, -12, -8, -24, -24, -8, -12, -12, -8, -12
        };
        int[] input = VanillaAquiferBatch.input(-2, -3, -2, 2, 3, 2, new int[]{-18, -18, -18}, centers);
        // Literal independent ranking: all four nearest cells tie; >= insertion reverses visitation order.
        return new KernelCase("aquifer-tied-neighbors", input, new int[]{7, 5, 6, 4});
    }

    private static KernelCase aquiferNegativeBoundaryCase() {
        int[] centers = {
            -39, -44, -39, -31, -44, -39, -39, -44, -31, -31, -44, -31,
            -39, -32, -39, -31, -32, -39, -39, -32, -31, -31, -32, -31,
            -39, -20, -39, -31, -20, -39, -39, -20, -31, -31, -20, -31
        };
        int[] input = VanillaAquiferBatch.input(-3, -4, -3, 2, 3, 2, new int[]{-35, -26, -35}, centers);
        // Literal independent ranking: eight equal nearest candidates leave the last four visits ranked.
        return new KernelCase("aquifer-negative-grid-edge", input, new int[]{11, 9, 7, 5});
    }

    private static int[] doubleWords(List<Double> values) {
        int[] words = new int[values.size() * 2];
        for (int i = 0; i < values.size(); i++) {
            long bits = Double.doubleToRawLongBits(values.get(i));
            words[i * 2] = (int)bits;
            words[i * 2 + 1] = (int)(bits >>> 32);
        }
        return words;
    }

    private static DensityFunction.FunctionContext context(int x, int y, int z) {
        return new DensityFunction.FunctionContext() {
            @Override public int blockX() { return x; }
            @Override public int blockY() { return y; }
            @Override public int blockZ() { return z; }
        };
    }

    private static VanillaNoiseBatch.ImprovedNoiseProfile snapshot(ImprovedNoise noise) {
        return new VanillaNoiseBatch.ImprovedNoiseProfile(noise.xo, noise.yo, noise.zo, (byte[])field(noise, "p"));
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
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not snapshot vanilla noise field " + name, failure);
        }
    }

    private static Object invokeDouble(Object owner, String method, int index) {
        try {
            return owner.getClass().getMethod(method, int.class).invoke(owner, index);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not read vanilla octave amplitude", failure);
        }
    }

    private static void configureHardwareTest() {
        GPurConfig.gpuAccelerationEnabled = true;
        GPurConfig.multiGpuEnabled = false;
        GPurConfig.gpuForce = true;
        GPurConfig.gpuDevices = List.of("auto");
        GPurConfig.gpuExecutionContexts = 4;
        GPurConfig.gpuUsageFallback = 100;
        GPurConfig.gpuTimeoutMillis = 5_000;
        GPurConfig.gpuAsyncSubmit = true;
        GPurConfig.gpuBatchMaxJobs = 4;
        GPurConfig.gpuBatchWaitMicros = 500;
        GPurConfig.gpuAsyncQueueCapacity = 64;
        GPurConfig.gpuBufferBudgetMiB = 128;
        GPurConfig.antiXrayGpuReservedContexts = 0;
    }

    private record KernelCase(String name, int[] input, int[] expected) {}

    private static final class ConfigSnapshot implements AutoCloseable {
        private final List<SavedField> fields;

        private ConfigSnapshot(List<SavedField> fields) { this.fields = fields; }

        private static ConfigSnapshot capture() throws IllegalAccessException {
            List<SavedField> saved = new ArrayList<>();
            for (Field field : GPurConfig.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isFinal(modifiers)) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof List<?> list) value = List.copyOf(list);
                saved.add(new SavedField(field, value));
            }
            return new ConfigSnapshot(List.copyOf(saved));
        }

        @Override
        public void close() throws IllegalAccessException {
            for (SavedField saved : this.fields) saved.field().set(null, saved.value());
        }
    }

    private record SavedField(Field field, Object value) {}
}
