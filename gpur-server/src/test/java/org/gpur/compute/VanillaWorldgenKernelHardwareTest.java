package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.CubicSpline;
import net.minecraft.data.worldgen.TerrainProvider;
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
import org.gpur.terrain.VanillaDensityGraph;
import org.gpur.terrain.VanillaDensityGraphBatch;
import org.gpur.terrain.VanillaOverworldCornerGraphFixture;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;

/** Opt-in physical Vulkan parity for exact worldgen kernels, vanilla spline and table graphs, and live corner adoption. */
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
    void everyPhysicalUuidPassesExactNoiseAquiferDensityGraphAndSplineKernelsUnderMixedConcurrency() throws Exception {
        List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        assertFalse(devices.isEmpty(), "Hardware test requires at least one Vulkan FP64 device");
        assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count(), "Device UUIDs must be unique");
        requireExpectedDeviceUuids(devices);
        List<KernelCase> cases = List.of(
            noiseCase(), aquiferFirstTieCase(), aquiferNegativeBoundaryCase(),
            densityGraphCase(), densityGradientSemanticsCase(),
            densityNoiseSplineCase(), terrainProviderSplineCase(),
            splineFp32SubnormalCase(), splineFp32AdversarialCase(), splineNegativeZeroExtensionCase(), splineFp32RoundingBoundaryCase(),
            defaultOverworldTableCase()
        );
        KernelCase fullHeight = seededAquiferCase(0, 0, 384);
        KernelCase ordinaryHeightNegativeChunk = seededAquiferCase(-32, -32, 128);

        try (ConfigSnapshot config = ConfigSnapshot.capture()) {
            configureHardwareTest();
            List<Throwable> failures = new ArrayList<>();
            for (Info info : devices) {
                runAndCollect(failures, info, "literal-and-mixed-cases", () -> verifyDevice(info, cases));
                runAndCollect(failures, info, "full-height-direct",
                    () -> verifyKernelBufferMode(info, fullHeight, "full-height-spawn-aquifer-direct", 0, false));
                runAndCollect(failures, info, "full-height-staged",
                    () -> verifyKernelBufferMode(info, fullHeight, "full-height-spawn-aquifer-staged", 65_536, true));
                runAndCollect(failures, info, "negative-chunk-128-staged",
                    () -> verifyKernelBufferMode(info, ordinaryHeightNegativeChunk,
                        "negative-chunk-128-aquifer", 65_536, true));
                for (KernelCase graphCase : cases) {
                    if (graphCase.input()[0] != VanillaDensityGraphBatch.WORKLOAD) continue;
                    runAndCollect(failures, info, graphCase.name() + "-direct",
                        () -> verifyKernelBufferMode(info, graphCase, graphCase.name() + "-direct", 0, false));
                    runAndCollect(failures, info, graphCase.name() + "-staged",
                        () -> verifyKernelBufferMode(info, graphCase, graphCase.name() + "-staged", 1, true));
                }
            }
            assertAll("Every physical UUID must pass all exact worldgen buffer modes",
                failures.stream().map(failure -> (Executable)() -> { throw failure; }).toList());
        }
    }

    @Test
    void everyPhysicalUuidPassesAllDefaultOverworldCornerRootsDirectAndStaged() throws Exception {
        List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        assertFalse(devices.isEmpty(), "Hardware test requires at least one Vulkan FP64 device");
        assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count(), "Device UUIDs must be unique");
        requireExpectedDeviceUuids(devices);
        List<KernelCase> cornerCases = defaultOverworldCornerCases();

        try (ConfigSnapshot config = ConfigSnapshot.capture()) {
            configureHardwareTest();
            List<Throwable> failures = new ArrayList<>();
            for (Info info : devices) {
                runAndCollect(failures, info, "all-eight-overworld-roots-direct",
                    () -> verifyCornerBufferMode(info, cornerCases, "all-eight-overworld-roots-direct", 0, false));
                runAndCollect(failures, info, "all-eight-overworld-roots-staged",
                    () -> verifyCornerBufferMode(info, cornerCases, "all-eight-overworld-roots-staged", 1, true));
            }
            assertAll("Every physical UUID must pass all default Overworld root corners in both buffer modes",
                failures.stream().map(failure -> (Executable)() -> { throw failure; }).toList());
        }
    }

    @EnabledIfSystemProperty(named = "gpur.gpu-live-corner-adoption-tests", matches = "true")
    @Test
    void everyPhysicalUuidAdoptsCompleteOverworldChunksThroughTheLiveNoiseChunkConsumer() throws Exception {
        List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        assertFalse(devices.isEmpty(), "Hardware test requires at least one Vulkan FP64 device");
        assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count(), "Device UUIDs must be unique");
        requireExpectedDeviceUuids(devices);

        try (ConfigSnapshot config = ConfigSnapshot.capture()) {
            for (Info info : devices) {
                configureHardwareTest();
                GPurConfig.gpuDevices = List.of(info.uuid());
                GPurConfig.terrainGpuEnabled = true;
                GPurConfig.vanillaTerrainEnabled = true;
                GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.STRICT;
                GPurConfig.vanillaTerrainVerifyEveryBatch = true;
                GPurConfig.vanillaTerrainMinValues = 1_024;
                GPurConfig.vanillaTerrainMaxSlabValues = 65_536;
                GPurConfig.vanillaNoiseGpuEnabled = false;
                GPurConfig.vanillaAquiferGpuEnabled = false;
                GPurConfig.vanillaCornerGraphsGpuEnabled = true;
                try (ComputeService service = new ComputeService(Logger.getLogger("gpur-corner-graph-physical-" + info.uuid()))) {
                    assertTrue(service.worldgenEligible(VanillaDensityGraphBatch.WORKLOAD),
                        info.name() + " must initialize the opt-in W7 service for its physical UUID");
                    long consumedBefore = service.worldgenDevices().stream()
                        .filter(status -> status.uuid().equals(info.uuid()) && status.workload() == VanillaDensityGraphBatch.WORKLOAD)
                        .mapToLong(ComputeService.WorldgenDeviceStatus::consumedValues).sum();
                    NoiseChunkCornerGraphAdoptionParityTest.compareCompleteChunk(service, 0, 0, false);
                    NoiseChunkCornerGraphAdoptionParityTest.compareCompleteChunk(service, -32, -48, false);
                    ComputeService.WorldgenDeviceStatus status = service.worldgenDevices().stream()
                        .filter(candidate -> candidate.uuid().equals(info.uuid())
                            && candidate.workload() == VanillaDensityGraphBatch.WORKLOAD)
                        .findFirst().orElseThrow(() -> new AssertionError("Missing W7 status for " + info.uuid()));
                    assertEquals(19_600, status.consumedValues() - consumedBefore,
                        info.name() + " must consume all eight root lattices from both complete chunks");
                    assertTrue(status.dispatches() > 0 && status.acceptedBatches() > 0,
                        info.name() + " must produce accepted physical W7 submissions");
                }
            }
        }
    }

    private static void runAndCollect(List<Throwable> failures, Info info, String label, CheckedAction action) {
        try {
            action.run();
        } catch (Exception | AssertionError failure) {
            failures.add(new AssertionError(info.name() + " " + label, failure));
        }
    }

    private static void requireExpectedDeviceUuids(final List<Info> devices) {
        String expectedProperty = System.getProperty("gpur.gpu-test-device-uuids", "").trim();
        if (expectedProperty.isEmpty()) return;
        Set<String> expected = Arrays.stream(expectedProperty.split(","))
            .map(String::trim)
            .filter(uuid -> !uuid.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
        assertFalse(expected.isEmpty(), "Expected Vulkan device UUID property must contain at least one UUID");
        Set<String> discovered = devices.stream().map(Info::uuid).collect(java.util.stream.Collectors.toSet());
        assertTrue(discovered.containsAll(expected), "Missing required Vulkan FP64 device UUIDs: "
            + expected.stream().filter(uuid -> !discovered.contains(uuid)).toList());
    }

    private static void verifyKernelBufferMode(
        Info info, KernelCase kernelCase, String label, int stagingThresholdBytes, boolean expectStaging
    ) throws Exception {
        GPurConfig.gpuDeviceLocalThresholdBytes = stagingThresholdBytes;
        GPurConfig.gpuExecutionContexts = 1;
        GPurConfig.gpuBatchMaxJobs = 1;
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name() + " must open by physical UUID " + info.uuid());
            if (kernelCase.input()[0] == VanillaDensityGraphBatch.WORKLOAD) {
                device.prepareDensityGraphPipeline();
            }
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
            System.out.println("GPur exact Vulkan " + info.name() + " uuid=" + info.uuid()
                + " workload=" + kernelCase.input()[0] + " case=" + label
                + " queries=" + kernelCase.input()[1] + " inputBytes=" + (long)kernelCase.input().length * Integer.BYTES
                + " outputBytes=" + (long)kernelCase.expected().length * Integer.BYTES
                + " bufferMode=" + (expectStaging ? "staging" : "direct") + " exact=true");
        }
    }

    private static void verifyDevice(Info info, List<KernelCase> cases) throws Exception {
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name() + " must open by physical UUID " + info.uuid());
            device.prepareDensityGraphPipeline();
            DeviceMetrics before = device.metrics();
            int completedJobs = 0;

            // Require a native result for each standalone kernel/profile family; no CPU fallback is used here.
            for (KernelCase kernelCase : cases) {
                int[] actual = device.computeAsync(
                    kernelCase.input(), ExactCompute.outputWords(kernelCase.input()), kernelCase.input()[1]
                ).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertKernelResult(info, kernelCase, actual, "single");
                completedJobs++;
            }

            // Mix workloads 5, 6 and 7 while the Vulkan worker is active.
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
                + " workload5Profiles=4 workload6LiteralCases=2 workload7GraphAndSplineCases="
                + cases.stream().filter(kernelCase -> kernelCase.input()[0] == VanillaDensityGraphBatch.WORKLOAD).count()
                + " nativeJobs=" + completedJobs
                + " nativeCompleted=" + (after.completedJobs() - before.completedJobs())
                + " nullResults=0 rejected=0 timedOut=0 failed=0");
        }
    }

    private static void verifyCornerBufferMode(
        Info info, List<KernelCase> cases, String label, int stagingThresholdBytes, boolean expectStaging
    ) throws Exception {
        GPurConfig.gpuDeviceLocalThresholdBytes = stagingThresholdBytes;
        GPurConfig.gpuExecutionContexts = 1;
        GPurConfig.gpuBatchMaxJobs = 1;
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name() + " must open by physical UUID " + info.uuid());
            device.prepareDensityGraphPipeline();
            DeviceMetrics before = device.metrics();
            int completedJobs = 0;
            for (KernelCase kernelCase : cases) {
                int[] actual = device.computeAsync(
                    kernelCase.input(), ExactCompute.outputWords(kernelCase.input()), kernelCase.input()[1]
                ).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertKernelResult(info, kernelCase, actual, label);
                assertEquals(expectStaging, deviceLocalMode(device), info.name() + " " + label + " buffer mode");
                completedJobs++;
            }
            DeviceMetrics after = device.metrics();
            assertEquals(completedJobs, after.submittedJobs() - before.submittedJobs(), info.name() + " native submissions");
            assertEquals(completedJobs, after.completedJobs() - before.completedJobs(), info.name() + " native completions");
            assertEquals(0, after.failedJobs() - before.failedJobs(), info.name() + " failed jobs");
            assertTrue(device.available(), info.name() + " remains available after default corner graph parity");
            System.out.println("GPur default Overworld W7 corner closure " + info.name() + " uuid=" + info.uuid()
                + " cases=" + cases.size() + " nativeJobs=" + completedJobs + " bufferMode="
                + (expectStaging ? "staging" : "direct") + " rawBitsExact=true");
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
        } else if (kernelCase.input()[0] == VanillaDensityGraphBatch.WORKLOAD
                && actual.length == kernelCase.expected().length) {
            int sample = firstDensityMismatch(actual, kernelCase.expected());
            if (sample >= 0) {
                int sampleOffset = kernelCase.input()[16] + sample * VanillaDensityGraphBatch.SAMPLE_WORDS;
                detail += " firstMismatchSample=" + sample + " xyz=" + kernelCase.input()[sampleOffset] + ","
                    + kernelCase.input()[sampleOffset + 1] + "," + kernelCase.input()[sampleOffset + 2]
                    + " expectedBits=0x" + Long.toHexString(Double.doubleToRawLongBits(
                        VanillaDensityGraphBatch.outputValue(kernelCase.expected(), sample)))
                    + " actualBits=0x" + Long.toHexString(Double.doubleToRawLongBits(
                        VanillaDensityGraphBatch.outputValue(actual, sample)));
            }
        }
        assertArrayEquals(kernelCase.expected(), actual, detail);
    }

    private static int firstDensityMismatch(int[] actual, int[] expected) {
        int mismatch = 0;
        while (mismatch < actual.length && actual[mismatch] == expected[mismatch]) mismatch++;
        return mismatch == actual.length ? -1 : mismatch / VanillaDensityGraphBatch.OUTPUT_WORDS_PER_SAMPLE;
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

    private static KernelCase densityGraphCase() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x7d1eL), -2, 0.75, 1.0, 0.375, 0.5);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction y = DensityFunctions.yClampedGradient(-64, 320, -0.3, 0.7);
        DensityFunction noise = DensityFunctions.noise(holder, 0.125, 0.0625);
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            DensityFunctions.shift(holder), DensityFunctions.shiftA(holder), 0.03125, holder
        );
        DensityFunction ranged = DensityFunctions.rangeChoice(
            y, -0.1, 0.2, shifted,
            DensityFunctions.add(DensityFunctions.shiftB(holder), DensityFunctions.mul(noise, DensityFunctions.constant(1.25)))
        );
        DensityFunction intervals = DensityFunctions.intervalSelect(
            y, new DoubleArrayList(new double[] {-0.3, -0.1, 0.0, 0.2}),
            List.of(noise, DensityFunctions.shift(holder), shifted, ranged, noise.quarterNegative())
        );
        DensityFunction graph = DensityFunctions.min(
            DensityFunctions.max(DensityFunctions.add(intervals, y), DensityFunctions.mul(ranged, shifted)),
            DensityFunctions.constant(0.75)
        );
        DensityFunction mappedInput = DensityFunctions.constant(-0.75);
        DensityFunction mappedBranches = DensityFunctions.intervalSelect(
            DensityFunctions.yClampedGradient(0, 6, 0.0, 6.0),
            new DoubleArrayList(new double[] {0.5, 1.5, 2.5, 3.5, 4.5, 5.5}),
            List.of(
                mappedInput.abs(), mappedInput.square(), mappedInput.cube(), mappedInput.halfNegative(),
                mappedInput.quarterNegative(), mappedInput.invert(), mappedInput.squeeze()
            )
        );
        DensityFunction blended = new BlendedNoise(
            RandomSource.create(0x991L), 1.0, 1.0, 8.55515, 4.277575, 2.0
        ).clamp(-0.75, 0.75);
        graph = DensityFunctions.add(DensityFunctions.add(graph, mappedBranches), blended);
        DensityFunction initialized = initializeDensityNoise(graph, normal);
        int[][] points = {
            {-31, -65, 47}, {-1, -64, -1}, {0, -1, 0}, {73, 64, -911},
            {17, 320, -19}, {13, 321, -5}, {-16, 128, 16}, {243, 311, -77},
            {0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {0, 4, 0}, {0, 5, 0}, {0, 6, 0}
        };
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(points.length);
        List<Double> expected = new ArrayList<>(points.length);
        for (int[] point : points) {
            samples.add(new VanillaDensityGraphBatch.Sample(point[0], point[1], point[2]));
            expected.add(initialized.compute(context(point[0], point[1], point[2])));
        }
        int[] input = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(initialized), samples);
        return new KernelCase("synthetic-density-graph-v1-operators", input, doubleWords(expected));
    }

    private static KernelCase densityGradientSemanticsCase() {
        DensityFunction signedZeroGradient = DensityFunctions.yClampedGradient(0, 1, 0.0, -0.0);
        double largeFiniteEndpoint = Double.MAX_VALUE / 4.0;
        DensityFunction extremeGradient = DensityFunctions.yClampedGradient(
            1, 3, largeFiniteEndpoint, -largeFiniteEndpoint
        );
        DensityFunction selector = DensityFunctions.yClampedGradient(0, 1, 1.0, 0.0);
        DensityFunction graph = DensityFunctions.rangeChoice(selector, 0.5, 1.5, extremeGradient, signedZeroGradient);
        int[][] points = {
            {-13, -10, 7}, {-3, 0, -19}, {0, 1, 0}, {9, 2, -27},
            {15, 3, 44}, {-32, 4, 23}, {81, 0, -7}, {-64, 2, 64}, {27, 10, -93}
        };
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(points.length);
        List<Double> expected = new ArrayList<>(points.length);
        for (int[] point : points) {
            samples.add(new VanillaDensityGraphBatch.Sample(point[0], point[1], point[2]));
            expected.add(graph.compute(context(point[0], point[1], point[2])));
        }
        int[] input = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(graph), samples);
        return new KernelCase("density-gradient-boundary-fp64", input, doubleWords(expected));
    }

    private static KernelCase densityNoiseSplineCase() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x7a91L), -2, 0.75, 1.0, 0.375, 0.5);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction noiseCoordinate = DensityFunctions.add(
            DensityFunctions.noise(holder, 0.125, 0.0625),
            DensityFunctions.yClampedGradient(-64, 64, -0.5, 0.5)
        );
        DensityFunctions.Spline.Coordinate noisy = new DensityFunctions.Spline.Coordinate(noiseCoordinate);
        DensityFunctions.Spline.Coordinate vertical = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(-64, 64, -2.0, 2.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> nested = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(noisy)
            .addPoint(-1.0F, -0.75F, 0.125F)
            .addPoint(0.0F, 0.25F, -0.375F)
            .addPoint(1.0F, 0.875F, 0.25F)
            .build();
        CubicSpline<DensityFunctions.Spline.Coordinate> outer = new CubicSpline.Multipoint<>(
            vertical,
            new float[] {-1.0F, -0.5F, 0.0F, 0.5F, 1.0F},
            List.of(
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(-0.5F),
                nested,
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(0.25F),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(-0.0F),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(1.25F)
            ),
            new float[] {0.125F, 0.25F, -0.5F, -0.0F, 0.25F}
        );
        DensityFunction initialized = initializeDensityNoise(new DensityFunctions.Spline(outer), normal);
        int[][] points = {
            {-911, -64, 47}, {-1, -32, -1}, {73, -24, -911}, {17, -16, -19},
            {-16, 0, 16}, {243, 16, -77}, {0, 32, 0}, {-13, 48, 7}, {9, 64, -27}
        };
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(points.length);
        List<Double> expected = new ArrayList<>(points.length);
        for (int[] point : points) {
            samples.add(new VanillaDensityGraphBatch.Sample(point[0], point[1], point[2]));
            expected.add(initialized.compute(context(point[0], point[1], point[2])));
        }
        int[] input = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(initialized), samples);
        return new KernelCase("initialized-noise-nested-spline-v2", input, doubleWords(expected));
    }

    private static KernelCase terrainProviderSplineCase() {
        DensityFunctions.Spline.Coordinate continents = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(-128, 128, -1.1, 1.0)
        );
        DensityFunctions.Spline.Coordinate erosion = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(-128, 128, -1.0, 1.0)
        );
        DensityFunctions.Spline.Coordinate weirdness = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(-96, 96, -1.0, 1.0)
        );
        DensityFunctions.Spline.Coordinate ridges = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(-80, 80, -1.0, 1.0)
        );
        DensityFunction offset = new DensityFunctions.Spline(
            TerrainProvider.overworldOffset(continents, erosion, ridges, false));
        DensityFunction factor = new DensityFunctions.Spline(
            TerrainProvider.overworldFactor(continents, erosion, weirdness, ridges, false));
        DensityFunction jaggedness = new DensityFunctions.Spline(
            TerrainProvider.overworldJaggedness(continents, erosion, weirdness, ridges, false));
        DensityFunction root = DensityFunctions.add(DensityFunctions.add(offset, factor), jaggedness);
        int[] ys = {-140, -128, -100, -80, -50, -30, -15, 0, 25, 50, 75, 100, 130};
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(ys.length);
        List<Double> expected = new ArrayList<>(ys.length);
        for (int i = 0; i < ys.length; i++) {
            int x = -113 + i * 17;
            int z = 257 - i * 31;
            samples.add(new VanillaDensityGraphBatch.Sample(x, ys[i], z));
            expected.add(root.compute(context(x, ys[i], z)));
        }
        int[] input = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(root), samples);
        return new KernelCase("terrain-provider-spline-synthetic-coordinates-v2", input, doubleWords(expected));
    }

    private static KernelCase splineFp32SubnormalCase() {
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.75)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> spline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(coordinate)
            .addPoint(0.0F, 0.0F)
            .addPoint(1.0F, Float.MIN_VALUE)
            .build();
        DensityFunction root = new DensityFunctions.Spline(spline);
        int[][] positions = {{0, 0, 0}, {-31, -64, 47}, {911, 64, -243}};
        KernelCase kernelCase = densitySplineCase("spline-fp32-subnormal-interpolation-v2", root, positions);
        long expectedBits = Double.doubleToRawLongBits((double)Float.MIN_VALUE);
        assertEquals(expectedBits, Integer.toUnsignedLong(kernelCase.expected()[0])
            | ((long)kernelCase.expected()[1] << 32), "actual Mache interpolation must retain the least positive float subnormal");
        return kernelCase;
    }

    private static KernelCase splineFp32AdversarialCase() {
        List<DensityFunction> branches = new ArrayList<>();
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>constant(Float.MIN_VALUE)));
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>constant(-Float.MIN_VALUE)));

        DensityFunctions.Spline.Coordinate subnormalCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant((double)Float.MIN_VALUE)
        );
        branches.add(new DensityFunctions.Spline(new CubicSpline.Multipoint<>(
            subnormalCoordinate,
            new float[] {-2.0F * Float.MIN_VALUE, 2.0F * Float.MIN_VALUE},
            List.of(
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(-Float.MIN_VALUE),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(Float.MIN_VALUE)
            ),
            new float[] {Float.MIN_VALUE, -Float.MIN_VALUE}
        )));

        DensityFunctions.Spline.Coordinate extensionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(-1.0)
        );
        branches.add(new DensityFunctions.Spline(new CubicSpline.Multipoint<>(
            extensionCoordinate,
            new float[] {0.0F, 1.0F},
            List.of(
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(0.0F),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(0.0F)
            ),
            new float[] {Float.MIN_VALUE, 0.0F}
        )));

        DensityFunctions.Spline.Coordinate transitionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.5)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> normalTransition = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(transitionCoordinate)
            .addPoint(0.0F, 0.0F)
            .addPoint(1.0F, Float.MIN_NORMAL)
            .build();
        branches.add(new DensityFunctions.Spline(normalTransition));

        DensityFunctions.Spline.Coordinate upperTransitionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(1.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> normalEndpoint = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(upperTransitionCoordinate)
            .addPoint(0.0F, 0.0F)
            .addPoint(1.0F, Float.MIN_NORMAL)
            .build();
        branches.add(new DensityFunctions.Spline(normalEndpoint));

        DensityFunctions.Spline.Coordinate zeroDivisionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> zeroFraction = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(zeroDivisionCoordinate)
            .addPoint(0.0F, 0.0F)
            .addPoint(3.0F, 1.0F)
            .build();
        branches.add(new DensityFunctions.Spline(zeroFraction));

        DensityFunctions.Spline.Coordinate oneThirdCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(1.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> oneThirdFraction = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(oneThirdCoordinate)
            .addPoint(0.0F, 0.0F, 1.0F / 3.0F)
            .addPoint(3.0F, 1.0F, 1.0F / 3.0F)
            .build();
        branches.add(new DensityFunctions.Spline(oneThirdFraction));

        DensityFunctions.Spline.Coordinate halfwayCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.5)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> subnormalMidpoint = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(halfwayCoordinate)
            .addPoint(0.0F, 0.0F)
            .addPoint(1.0F, Float.MIN_VALUE)
            .build();
        branches.add(new DensityFunctions.Spline(subnormalMidpoint));

        float wide = Float.MAX_VALUE / 4.0F;
        DensityFunctions.Spline.Coordinate wideCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> wideFinite = new CubicSpline.Multipoint<>(
            wideCoordinate,
            new float[] {-wide, wide},
            List.of(
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(wide),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(wide * 2.0F)
            ),
            new float[] {0.0F, 0.0F}
        );
        branches.add(new DensityFunctions.Spline(wideFinite));

        int branchCount = branches.size();
        double[] thresholds = new double[branchCount - 1];
        int[][] positions = new int[branchCount][3];
        for (int branch = 0; branch < branchCount; branch++) {
            positions[branch] = new int[] {branch - 7, branch, 11 - branch};
            if (branch < thresholds.length) thresholds[branch] = branch + 0.5;
        }
        DensityFunction selector = DensityFunctions.yClampedGradient(0, branchCount - 1, 0.0, branchCount - 1.0);
        DensityFunction root = DensityFunctions.intervalSelect(selector, new DoubleArrayList(thresholds), branches);
        KernelCase result = densitySplineCase("spline-fp32-adversarial-mache-v2", root, positions);
        assertEquals(Double.doubleToRawLongBits((double)Float.MIN_VALUE), rawBits(result.expected(), 0),
            "positive spline constant must preserve a subnormal binary32 value");
        assertEquals(Double.doubleToRawLongBits((double)-Float.MIN_VALUE), rawBits(result.expected(), 1),
            "negative spline constant must preserve a subnormal binary32 value");
        assertEquals(Double.doubleToRawLongBits((double)(Float.MIN_NORMAL / 2.0F)), rawBits(result.expected(), 4),
            "normal-to-subnormal interpolation must retain its binary32 midpoint");
        assertEquals(Double.doubleToRawLongBits((double)Float.MIN_NORMAL), rawBits(result.expected(), 5),
            "subnormal-to-normal transition must retain the least normal binary32 value");
        assertEquals(Double.doubleToRawLongBits((double)(1.0F / 3.0F)), rawBits(result.expected(), 7),
            "spline division must round the one-third fraction as binary32");
        assertEquals(Double.doubleToRawLongBits(0.0), rawBits(result.expected(), 8),
            "half of the least subnormal must round to even zero");
        assertEquals(Double.doubleToRawLongBits((double)(wide * 1.5F)), rawBits(result.expected(), 9),
            "wide finite spline knots and values must remain finite and overflow-safe");
        return result;
    }

    private static long rawBits(int[] words, int sample) {
        return Integer.toUnsignedLong(words[sample * 2]) | ((long)words[sample * 2 + 1] << 32);
    }

    private static KernelCase splineNegativeZeroExtensionCase() {
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(-1.0)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> spline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(coordinate)
            .addPoint(0.0F, -0.0F, -0.0F)
            .addPoint(1.0F, 1.0F)
            .build();
        DensityFunction root = new DensityFunctions.Spline(spline);
        KernelCase kernelCase = densitySplineCase("spline-negative-zero-derivative-extension-v2", root,
            new int[][] {{0, 0, 0}, {-31, -64, 47}});
        assertEquals(Double.doubleToRawLongBits(-0.0), Integer.toUnsignedLong(kernelCase.expected()[0])
            | ((long)kernelCase.expected()[1] << 32), "actual Mache zero-derivative extension must preserve negative zero");
        return kernelCase;
    }

    private static KernelCase splineFp32RoundingBoundaryCase() {
        float firstLocation = 0.5F;
        float secondLocation = Math.nextUp(Math.nextUp(firstLocation));
        double halfway = ((double)firstLocation + (double)Math.nextUp(firstLocation)) * 0.5;
        double justAboveHalfway = Math.nextUp(halfway);
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.yClampedGradient(0, 1, halfway, justAboveHalfway)
        );
        CubicSpline<DensityFunctions.Spline.Coordinate> spline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(coordinate)
            .addPoint(firstLocation, 0.0F)
            .addPoint(secondLocation, 1.0F)
            .build();
        DensityFunction root = new DensityFunctions.Spline(spline);
        KernelCase kernelCase = densitySplineCase("spline-fp32-rounding-boundary-v2", root,
            new int[][] {{0, -1, 0}, {0, 0, 0}, {0, 1, 0}, {0, 2, 0}});
        int[] expected = kernelCase.expected();
        assertEquals(Double.doubleToRawLongBits(0.0), Integer.toUnsignedLong(expected[0]) | ((long)expected[1] << 32),
            "midpoint must round to the even lower binary32 coordinate");
        assertEquals(Double.doubleToRawLongBits(0.0), Integer.toUnsignedLong(expected[2]) | ((long)expected[3] << 32),
            "midpoint sample must remain on the lower knot");
        assertEquals(Double.doubleToRawLongBits(0.5), Integer.toUnsignedLong(expected[4]) | ((long)expected[5] << 32),
            "next-up midpoint must round to the adjacent binary32 coordinate");
        assertEquals(Double.doubleToRawLongBits(0.5), Integer.toUnsignedLong(expected[6]) | ((long)expected[7] << 32),
            "clamped upper coordinate must preserve the adjacent-float spline result");
        return kernelCase;
    }

    private static KernelCase densitySplineCase(String name, DensityFunction root, int[][] positions) {
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(positions.length);
        List<Double> expected = new ArrayList<>(positions.length);
        for (int[] position : positions) {
            samples.add(new VanillaDensityGraphBatch.Sample(position[0], position[1], position[2]));
            expected.add(root.compute(context(position[0], position[1], position[2])));
        }
        int[] input = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(root), samples);
        return new KernelCase(name, input, doubleWords(expected));
    }

    private static KernelCase defaultOverworldTableCase() throws Exception {
        VanillaOverworldCornerGraphFixture.RootCase tableRoot = VanillaOverworldCornerGraphFixture.capture(0, 0).stream()
            .filter(root -> root.graph().tableValueCount() > 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Default Overworld fixture must contain a captured FlatCache table"));
        List<VanillaDensityGraphBatch.Sample> samples = tableRoot.samples().subList(0, 49);
        int[] expected = Arrays.copyOfRange(tableRoot.expectedWords(), 0, samples.size() * 2);
        int[] input = VanillaDensityGraphBatch.input(tableRoot.graph(), samples);
        return new KernelCase("default-overworld-flatcache-root-" + tableRoot.rootIndex(), input, expected);
    }

    private static List<KernelCase> defaultOverworldCornerCases() throws Exception {
        List<KernelCase> cases = new ArrayList<>();
        int[][] origins = {{0, 0}, {-32, -48}};
        final int samplesPerColumn = 49;
        final int columnsPerBatch = 4;
        final int samplesPerBatch = samplesPerColumn * columnsPerBatch;
        for (int[] origin : origins) {
            List<VanillaOverworldCornerGraphFixture.RootCase> roots =
                VanillaOverworldCornerGraphFixture.capture(origin[0], origin[1]);
            assertEquals(8, roots.size(), "the complete default Overworld graph fixture has eight roots");
            for (VanillaOverworldCornerGraphFixture.RootCase root : roots) {
                assertEquals(1_225, root.samples().size(), "default Overworld fixture keeps all 1,225 corners per root");
                int[] expectedWords = root.expectedWords();
                for (int first = 0; first < root.samples().size(); first += samplesPerBatch) {
                    int end = Math.min(root.samples().size(), first + samplesPerBatch);
                    List<VanillaDensityGraphBatch.Sample> samples = root.samples().subList(first, end);
                    int[] input = VanillaDensityGraphBatch.input(root.graph(), samples);
                    int[] expected = Arrays.copyOfRange(expectedWords, first * 2, end * 2);
                    cases.add(new KernelCase("overworld-root-" + root.rootIndex() + "-origin-" + origin[0] + "-" + origin[1]
                        + "-samples-" + first + "-" + end, input, expected));
                }
            }
        }
        return List.copyOf(cases);
    }

    private static DensityFunction initializeDensityNoise(DensityFunction graph, NormalNoise normal) {
        return graph.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction input) { return input; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder input) {
                return new DensityFunction.NoiseHolder(input.noiseData(), normal);
            }
        });
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
