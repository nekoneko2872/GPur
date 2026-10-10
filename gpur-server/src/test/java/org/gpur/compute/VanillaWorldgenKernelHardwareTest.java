package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.gpur.GPurConfig;
import org.gpur.compute.VulkanDevice.DeviceMetrics;
import org.gpur.compute.VulkanDevice.Info;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/** Opt-in physical Vulkan parity checks for exact vanilla noise and aquifer ranking. */
@EnabledIfSystemProperty(named = "gpur.gpu-tests", matches = "true")
@VanillaFeature
final class VanillaWorldgenKernelHardwareTest {
    private static final int MIXED_ROUNDS = 4;
    private static final long FUTURE_TIMEOUT_SECONDS = 60;
    private static final long WORLD_SEED = 1_196_459_378L;
    private static final int MIN_Y = -64;
    private static final Field PRELIMINARY_SURFACE_LEVEL = field(NoiseChunk.class, "preliminarySurfaceLevel");
    private static final Field MIN_GRID_X = field(Aquifer.NoiseBasedAquifer.class, "minGridX");
    private static final Field MIN_GRID_Y = field(Aquifer.NoiseBasedAquifer.class, "minGridY");
    private static final Field MIN_GRID_Z = field(Aquifer.NoiseBasedAquifer.class, "minGridZ");
    private static final Field GRID_SIZE_X = field(Aquifer.NoiseBasedAquifer.class, "gridSizeX");
    private static final Field GRID_SIZE_Z = field(Aquifer.NoiseBasedAquifer.class, "gridSizeZ");
    private static final Field AQUIFER_LOCATION_CACHE = field(Aquifer.NoiseBasedAquifer.class, "aquiferLocationCache");
    private static final Field POSITIONAL_RANDOM_FACTORY = field(Aquifer.NoiseBasedAquifer.class, "positionalRandomFactory");

    @Test
    void everyPhysicalUuidPassesExactNoiseAndLiteralAquiferKernelsUnderMixedConcurrency() throws Exception {
        List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        assertFalse(devices.isEmpty(), "Hardware test requires at least one Vulkan FP64 device");
        assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count(), "Device UUIDs must be unique");
        List<KernelCase> cases = List.of(noiseCase(), aquiferFirstTieCase(), aquiferNegativeBoundaryCase());
        KernelCase fullHeight = seededAquiferCase(0, 0, 384);
        KernelCase ordinaryHeightNegativeChunk = seededAquiferCase(-32, -32, 128);

        try (ConfigSnapshot config = ConfigSnapshot.capture()) {
            configureHardwareTest();
            List<Throwable> failures = new ArrayList<>();
            for (Info info : devices) {
                runAndCollect(failures, info, "literal-and-mixed-cases", () -> verifyDevice(info, cases));
                runAndCollect(failures, info, "full-height-direct",
                    () -> verifyAquiferBufferMode(info, fullHeight, "full-height-spawn-aquifer-direct", 0, false));
                runAndCollect(failures, info, "full-height-staged",
                    () -> verifyAquiferBufferMode(info, fullHeight, "full-height-spawn-aquifer-staged", 65_536, true));
                runAndCollect(failures, info, "negative-chunk-128-staged",
                    () -> verifyAquiferBufferMode(info, ordinaryHeightNegativeChunk,
                        "negative-chunk-128-aquifer", 65_536, true));
            }
            assertAll("Every physical UUID must pass all exact worldgen buffer modes",
                failures.stream().map(failure -> (Executable)() -> { throw failure; }).toList());
        }
    }

    private static void runAndCollect(List<Throwable> failures, Info info, String label, CheckedAction action) {
        try {
            action.run();
        } catch (Exception | AssertionError failure) {
            failures.add(new AssertionError(info.name() + " " + label, failure));
        }
    }

    private static void verifyAquiferBufferMode(
        Info info, KernelCase kernelCase, String label, int stagingThresholdBytes, boolean expectStaging
    ) throws Exception {
        GPurConfig.gpuDeviceLocalThresholdBytes = stagingThresholdBytes;
        GPurConfig.gpuExecutionContexts = 1;
        GPurConfig.gpuBatchMaxJobs = 1;
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name() + " must open by physical UUID " + info.uuid());
            DeviceMetrics before = device.metrics();
            int[] actual = device.computeAsync(
                kernelCase.input(), ExactCompute.outputWords(kernelCase.input()), kernelCase.input()[1]
            ).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertKernelResult(info, kernelCase, actual, label);
            assertEquals(expectStaging, deviceLocalMode(device), info.name() + " " + label + " buffer mode");
            DeviceMetrics metrics = device.metrics();
            assertEquals(1, metrics.submittedJobs() - before.submittedJobs(), info.name() + " " + label + " native submission");
            assertEquals(1, metrics.completedJobs() - before.completedJobs(), info.name() + " " + label + " native completion");
            assertEquals(0, metrics.failedJobs() - before.failedJobs(), info.name() + " " + label + " failed jobs");
            assertTrue(device.available(), info.name() + " remains available after " + label);
            System.out.println("GPur aquifer Vulkan " + info.name() + " uuid=" + info.uuid() + " case=" + label
                + " queries=" + kernelCase.input()[1] + " inputBytes=" + (long)kernelCase.input().length * Integer.BYTES
                + " outputBytes=" + (long)kernelCase.expected().length * Integer.BYTES
                + " bufferMode=" + (expectStaging ? "staging" : "direct") + " exact=true");
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
        String detail = info.name() + " " + label + " " + kernelCase.name() + " raw output";
        if (kernelCase.input()[0] == VanillaAquiferBatch.WORKLOAD && actual.length == kernelCase.expected().length) {
            int firstMismatch = 0;
            while (firstMismatch < actual.length && actual[firstMismatch] == kernelCase.expected()[firstMismatch]) firstMismatch++;
            if (firstMismatch < actual.length) {
                int query = firstMismatch / VanillaAquiferBatch.RANK_COUNT;
                int rank = firstMismatch % VanillaAquiferBatch.RANK_COUNT;
                int queryOffset = VanillaAquiferBatch.HEADER_WORDS + query * 3;
                int[] input = kernelCase.input();
                detail += " firstMismatchWord=" + firstMismatch + " query=" + input[queryOffset] + ","
                    + input[queryOffset + 1] + "," + input[queryOffset + 2] + " rank=" + rank
                    + " expectedCenter=" + kernelCase.expected()[firstMismatch] + " actualCenter=" + actual[firstMismatch];
            }
        }
        assertArrayEquals(kernelCase.expected(), actual, detail);
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

    private static KernelCase seededAquiferCase(int chunkMinX, int chunkMinZ, int height) throws Exception {
        WorldgenFixture worldgen = worldgenFixture();
        NoiseGeneratorSettings settings = height == 384
            ? worldgen.overworldSettings()
            : dimensionSettings(worldgen.overworldSettings(), height);
        RandomState randomState = RandomState.create(settings, worldgen.noiseLookup(), WORLD_SEED);
        NoiseChunk noiseChunk = new NoiseChunk(
            1,
            randomState,
            chunkMinX,
            chunkMinZ,
            settings.noiseSettings(),
            TestBeardifier.INSTANCE,
            settings,
            (x, y, z) -> new Aquifer.FluidStatus(1_000, Blocks.WATER.defaultBlockState()),
            Blender.empty()
        );
        PRELIMINARY_SURFACE_LEVEL.set(noiseChunk, DensityFunctions.constant(-10_000.0));
        Aquifer.NoiseBasedAquifer aquifer = (Aquifer.NoiseBasedAquifer)noiseChunk.aquifer();
        int minGridX = MIN_GRID_X.getInt(aquifer);
        int minGridY = MIN_GRID_Y.getInt(aquifer);
        int minGridZ = MIN_GRID_Z.getInt(aquifer);
        int gridSizeX = GRID_SIZE_X.getInt(aquifer);
        int gridSizeZ = GRID_SIZE_Z.getInt(aquifer);
        long[] locationCache = (long[])AQUIFER_LOCATION_CACHE.get(aquifer);
        int gridSizeY = locationCache.length / (gridSizeX * gridSizeZ);
        Object randomFactory = POSITIONAL_RANDOM_FACTORY.get(aquifer);
        Method at = randomFactory.getClass().getMethod("at", int.class, int.class, int.class);
        for (int cellIndex = 0; cellIndex < locationCache.length; cellIndex++) {
            if (locationCache[cellIndex] != Long.MAX_VALUE) continue;
            int xIndex = cellIndex % gridSizeX;
            int zIndex = cellIndex / gridSizeX % gridSizeZ;
            int yIndex = cellIndex / (gridSizeX * gridSizeZ);
            int gridX = minGridX + xIndex;
            int gridY = minGridY + yIndex;
            int gridZ = minGridZ + zIndex;
            RandomSource random = (RandomSource)at.invoke(randomFactory, gridX, gridY, gridZ);
            locationCache[cellIndex] = BlockPos.asLong(
                gridX * 16 + random.nextInt(10),
                gridY * 12 + random.nextInt(9),
                gridZ * 16 + random.nextInt(10)
            );
        }

        int queryCount = Math.multiplyExact(height, 256);
        int[] queries = new int[queryCount * 3];
        int queryIndex = 0;
        for (int y = MIN_Y; y < MIN_Y + height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int offset = queryIndex++ * 3;
                    queries[offset] = chunkMinX + x;
                    queries[offset + 1] = y;
                    queries[offset + 2] = chunkMinZ + z;
                }
            }
        }
        int[] centers = new int[locationCache.length * 3];
        for (int cellIndex = 0; cellIndex < locationCache.length; cellIndex++) {
            long location = locationCache[cellIndex];
            int offset = cellIndex * 3;
            centers[offset] = BlockPos.getX(location);
            centers[offset + 1] = BlockPos.getY(location);
            centers[offset + 2] = BlockPos.getZ(location);
        }
        int[] input = VanillaAquiferBatch.input(
            minGridX, minGridY, minGridZ, gridSizeX, gridSizeY, gridSizeZ, queries, centers
        );
        return new KernelCase("aquifer-seed-" + WORLD_SEED + "-chunk-" + chunkMinX + "-" + chunkMinZ, input,
            ExactCompute.reference(input));
    }

    private static NoiseGeneratorSettings dimensionSettings(NoiseGeneratorSettings original, int height) {
        NoiseSettings size = NoiseSettings.create(
            MIN_Y,
            height,
            original.noiseSettings().noiseSizeHorizontal(),
            original.noiseSettings().noiseSizeVertical()
        );
        return new NoiseGeneratorSettings(
            size,
            original.defaultBlock(),
            original.defaultFluid(),
            original.noiseRouter(),
            original.surfaceRule(),
            original.spawnTarget(),
            original.seaLevel(),
            original.disableMobGeneration(),
            original.isAquifersEnabled(),
            original.oreVeinsEnabled(),
            original.useLegacyRandomSource()
        );
    }

    private static WorldgenFixture worldgenFixture() {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
        return new WorldgenFixture(settings, noises);
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean deviceLocalMode(VulkanDevice device) throws Exception {
        Field contextsField = VulkanDevice.class.getDeclaredField("contexts");
        contextsField.setAccessible(true);
        BlockingQueue<?> contexts = (BlockingQueue<?>)contextsField.get(device);
        assertEquals(1, contexts.size(), "The execution context must return after readback");
        Object context = contexts.peek();
        Method deviceLocal = context.getClass().getDeclaredMethod("deviceLocal");
        deviceLocal.setAccessible(true);
        return (boolean)deviceLocal.invoke(context);
    }

    private record WorldgenFixture(
        NoiseGeneratorSettings overworldSettings,
        HolderLookup<NormalNoise.NoiseParameters> noiseLookup
    ) {}

    private enum TestBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override public double compute(DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
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

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

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
