package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.GPurConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runtime adoption/fallback checks around the production NoiseBasedAquifer. */
@VanillaFeature
class AquiferRuntimeGpuRankingTest {
    private static final int CHUNK_MIN_X = -32;
    private static final int CHUNK_MIN_Z = -32;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 128;
    private static final long WORLD_SEED = 0x51a7_6eL;
    private static final Field PRELIMINARY_SURFACE_LEVEL = field(NoiseChunk.class, "preliminarySurfaceLevel");
    private static final Field PRELIMINARY_SURFACE_LEVEL_CACHE = field(NoiseChunk.class, "preliminarySurfaceLevelCache");
    private static final Field SKIP_SAMPLING_ABOVE_Y = field(Aquifer.NoiseBasedAquifer.class, "skipSamplingAboveY");
    private static final Field AQUIFER_LOCATION_CACHE = field(Aquifer.NoiseBasedAquifer.class, "aquiferLocationCache");
    private static final Field AQUIFER_CACHE = field(Aquifer.NoiseBasedAquifer.class, "aquiferCache");
    private final ConfigSnapshot previous = ConfigSnapshot.capture();

    @BeforeEach
    void enableStrictWorldgen() {
        GPurConfig.gpuAccelerationEnabled = true;
        GPurConfig.terrainGpuEnabled = true;
        GPurConfig.gpuAsyncSubmit = true;
        GPurConfig.gpuBufferBudgetMiB = 32;
        GPurConfig.vanillaTerrainEnabled = true;
        GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.STRICT;
        GPurConfig.vanillaTerrainVerifyEveryBatch = true;
        GPurConfig.vanillaTerrainMinValues = 1;
        GPurConfig.vanillaTerrainMaxSlabValues = 65_536;
        GPurConfig.vanillaNoiseGpuEnabled = true;
        GPurConfig.vanillaAquiferGpuEnabled = true;
    }

    @AfterEach
    void restoreConfig() {
        this.previous.restore();
    }

    @Test
    void installedGpuRanksMatchVanillaAcrossNegativeChunkAndKeepLazyStatuses() throws Exception {
        AquiferFixture cpu = fixture();
        AquiferFixture ranked = fixture();
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });

        try (ComputeService service = new ComputeService(Logger.getLogger("aquifer-runtime-parity"), List.of(device))) {
            WorldgenResult frame = ranked.aquifer().gpurPrepareRanking(
                service, CHUNK_MIN_X, CHUNK_MIN_Z, MIN_Y, HEIGHT, Runnable::run
            ).join();
            assertNotNull(frame, "the exact mock backend should return a strictly verified ranking frame");
            ranked.aquifer().gpurInstallRanking(frame);
            assertTrue(frame.usable());

            compareSubstance(cpu.aquifer(), ranked.aquifer(), -18, -18, -18);
            int loadedStatuses = populatedStatuses(cpu.aquifer()).length;
            assertTrue(loadedStatuses > 0 && loadedStatuses <= 4,
                "one vanilla computeSubstance call should lazily load only its closest status set");
            assertArrayEquals(statuses(cpu.aquifer()), statuses(ranked.aquifer()),
                "rank adoption must preserve the original lazy status-cache selection");

            // Visit every block in the aquifer grid's query window. The varied picker exercises
            // water/lava and unequal fluid levels, so both pressure and early-result branches run.
            for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                for (int z = CHUNK_MIN_Z; z < CHUNK_MIN_Z + 16; z++) {
                    for (int x = CHUNK_MIN_X; x < CHUNK_MIN_X + 16; x++) {
                        compareSubstance(cpu.aquifer(), ranked.aquifer(), x, y, z);
                    }
                }
            }

            assertArrayEquals(locations(cpu.aquifer()), locations(ranked.aquifer()),
                "positional RNG must produce the same center for every grid cell");
            assertArrayEquals(statuses(cpu.aquifer()), statuses(ranked.aquifer()),
                "the full scan must preserve pressure/status lazy caching");
            assertEquals((long)16 * 16 * HEIGHT + 1, worldgenStatus(service).consumedValues(),
                "only queries that actually used an installed rank count as consumed work");

            // Closing an installed frame invalidates it in place. The next call must use the
            // original 12-cell CPU ranking loop and return exactly the same state and flag.
            frame.close();
            assertFalse(frame.usable());
            compareSubstance(cpu.aquifer(), ranked.aquifer(), -29, -37, -25);
            assertEquals((long)16 * 16 * HEIGHT + 1, worldgenStatus(service).consumedValues());
        }
    }

    @Test
    void parityFailureMakesInstalledRankingStaleAndFallsBackToVanilla() throws Exception {
        AquiferFixture cpu = fixture();
        AquiferFixture ranked = fixture();
        AtomicBoolean corruptNoise = new AtomicBoolean();
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            int[] output = ExactCompute.reference(input);
            if (input[0] == VanillaNoiseBatch.WORKLOAD && corruptNoise.get()) output[0] ^= 1;
            return CompletableFuture.completedFuture(output);
        });

        try (ComputeService service = new ComputeService(Logger.getLogger("aquifer-stale-ranking"), List.of(device))) {
            WorldgenResult frame = ranked.aquifer().gpurPrepareRanking(
                service, CHUNK_MIN_X, CHUNK_MIN_Z, MIN_Y, HEIGHT, Runnable::run
            ).join();
            assertNotNull(frame);
            ranked.aquifer().gpurInstallRanking(frame);
            assertTrue(frame.usable());

            corruptNoise.set(true);
            assertNull(service.prepareWorldgenAsync(noiseInput(), Runnable::run).join(),
                "strict mismatch must reject the new frame and invalidate older results");
            assertFalse(frame.usable(), "the service epoch invalidates already installed aquifer ranks");

            compareSubstance(cpu.aquifer(), ranked.aquifer(), -18, -18, -18);
            assertArrayEquals(statuses(cpu.aquifer()), statuses(ranked.aquifer()),
                "a stale result must fall back to the original candidate loop");
            assertEquals(0, worldgenStatus(service).consumedValues());
        }
    }

    @Test
    void abortingInterpolationAllowsTheSameNoiseChunkToBeInitializedAgain() throws Exception {
        NoiseChunk noiseChunk = fixture().noiseChunk();
        noiseChunk.initializeForFirstCellX();
        noiseChunk.gpurAbortInterpolation();
        assertDoesNotThrow(noiseChunk::initializeForFirstCellX,
            "cancellation cleanup must reset interpolation state before a chunk retry");
        noiseChunk.stopInterpolation();
    }

    private static AquiferFixture fixture() throws Exception {
        WorldgenFixture worldgen = worldgenFixture();
        NoiseGeneratorSettings settings = smallOverworldSettings(worldgen.settings());
        RandomState randomState = RandomState.create(settings, worldgen.noises(), WORLD_SEED);
        NoiseChunk noiseChunk = new NoiseChunk(
            1,
            randomState,
            CHUNK_MIN_X,
            CHUNK_MIN_Z,
            settings.noiseSettings(),
            TestBeardifier.INSTANCE,
            settings,
            AquiferRuntimeGpuRankingTest::fluidAt,
            Blender.empty()
        );
        PRELIMINARY_SURFACE_LEVEL.set(noiseChunk, DensityFunctions.constant(-10_000.0));
        ((Long2IntMap)PRELIMINARY_SURFACE_LEVEL_CACHE.get(noiseChunk)).clear();
        Aquifer.NoiseBasedAquifer aquifer = (Aquifer.NoiseBasedAquifer)noiseChunk.aquifer();
        SKIP_SAMPLING_ABOVE_Y.setInt(aquifer, Integer.MAX_VALUE);
        return new AquiferFixture(aquifer, noiseChunk);
    }

    private static Aquifer.FluidStatus fluidAt(int x, int y, int z) {
        // Keep block queries in the target chunk out of the lava short circuit. Centers outside
        // it vary type and level to exercise pressure and flowing-update decisions.
        if (x >= CHUNK_MIN_X && x < CHUNK_MIN_X + 16 && z >= CHUNK_MIN_Z && z < CHUNK_MIN_Z + 16) {
            return new Aquifer.FluidStatus(128, Blocks.WATER.defaultBlockState());
        }
        int key = x * 31 + y * 17 + z * 13;
        BlockState type = Math.floorMod(key, 7) == 0 ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
        int level = -48 + Math.floorMod(key * 19, 113);
        return new Aquifer.FluidStatus(level, type);
    }

    private static void compareSubstance(Aquifer expected, Aquifer actual, int x, int y, int z) {
        BlockState expectedState = expected.computeSubstance(new DensityFunction.SinglePointContext(x, y, z), -1.0);
        boolean expectedUpdate = expected.shouldScheduleFluidUpdate();
        BlockState actualState = actual.computeSubstance(new DensityFunction.SinglePointContext(x, y, z), -1.0);
        boolean actualUpdate = actual.shouldScheduleFluidUpdate();
        assertEquals(expectedState, actualState, () -> "block state at " + x + "," + y + "," + z);
        assertEquals(expectedUpdate, actualUpdate, () -> "fluid update flag at " + x + "," + y + "," + z);
    }

    private static long[] locations(Aquifer.NoiseBasedAquifer aquifer) throws IllegalAccessException {
        return ((long[])AQUIFER_LOCATION_CACHE.get(aquifer)).clone();
    }

    private static Aquifer.FluidStatus[] statuses(Aquifer.NoiseBasedAquifer aquifer) throws IllegalAccessException {
        return ((Aquifer.FluidStatus[])AQUIFER_CACHE.get(aquifer)).clone();
    }

    private static int[] populatedStatuses(Aquifer.NoiseBasedAquifer aquifer) throws IllegalAccessException {
        Aquifer.FluidStatus[] cache = statuses(aquifer);
        int[] indices = new int[cache.length];
        int count = 0;
        for (int index = 0; index < cache.length; index++) if (cache[index] != null) indices[count++] = index;
        return Arrays.copyOf(indices, count);
    }

    private static ComputeService.WorldgenDeviceStatus worldgenStatus(ComputeService service) {
        return service.worldgenDevices().stream()
            .filter(status -> status.workload() == VanillaAquiferBatch.WORKLOAD)
            .findFirst().orElseThrow();
    }

    private static int[] noiseInput() {
        byte[] permutation = new byte[256];
        for (int i = 0; i < permutation.length; i++) permutation[i] = (byte)i;
        VanillaNoiseBatch.ImprovedNoiseProfile profile = new VanillaNoiseBatch.ImprovedNoiseProfile(1.25, -2.5, 3.75, permutation);
        return VanillaNoiseBatch.input(new VanillaNoiseBatch.NoiseSample(profile, -17.25, 2.5, 91.125, 0.25, 0.125));
    }

    private static VulkanDevice backend() {
        VulkanDevice device = mock(VulkanDevice.class);
        AtomicBoolean available = new AtomicBoolean(true);
        when(device.available()).thenAnswer(call -> available.get());
        when(device.uuid()).thenReturn("aquifer-runtime-mock");
        when(device.name()).thenReturn("Aquifer runtime mock");
        doAnswer(call -> { available.set(false); return null; }).when(device).disable();
        return device;
    }

    private static NoiseGeneratorSettings smallOverworldSettings(NoiseGeneratorSettings original) {
        NoiseSettings size = NoiseSettings.create(
            MIN_Y,
            HEIGHT,
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

    private record AquiferFixture(Aquifer.NoiseBasedAquifer aquifer, NoiseChunk noiseChunk) {}
    private record WorldgenFixture(NoiseGeneratorSettings settings, HolderLookup<NormalNoise.NoiseParameters> noises) {}

    private enum TestBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
    }

    private record ConfigSnapshot(
        boolean gpuAccelerationEnabled,
        boolean terrainGpuEnabled,
        boolean gpuAsyncSubmit,
        int gpuBufferBudgetMiB,
        boolean vanillaTerrainEnabled,
        VanillaVerificationPolicy.Mode vanillaTerrainMode,
        boolean vanillaTerrainVerifyEveryBatch,
        int vanillaTerrainMinValues,
        int vanillaTerrainMaxSlabValues,
        boolean vanillaNoiseGpuEnabled,
        boolean vanillaAquiferGpuEnabled
    ) {
        static ConfigSnapshot capture() {
            return new ConfigSnapshot(
                GPurConfig.gpuAccelerationEnabled,
                GPurConfig.terrainGpuEnabled,
                GPurConfig.gpuAsyncSubmit,
                GPurConfig.gpuBufferBudgetMiB,
                GPurConfig.vanillaTerrainEnabled,
                GPurConfig.vanillaTerrainMode,
                GPurConfig.vanillaTerrainVerifyEveryBatch,
                GPurConfig.vanillaTerrainMinValues,
                GPurConfig.vanillaTerrainMaxSlabValues,
                GPurConfig.vanillaNoiseGpuEnabled,
                GPurConfig.vanillaAquiferGpuEnabled
            );
        }

        void restore() {
            GPurConfig.gpuAccelerationEnabled = this.gpuAccelerationEnabled;
            GPurConfig.terrainGpuEnabled = this.terrainGpuEnabled;
            GPurConfig.gpuAsyncSubmit = this.gpuAsyncSubmit;
            GPurConfig.gpuBufferBudgetMiB = this.gpuBufferBudgetMiB;
            GPurConfig.vanillaTerrainEnabled = this.vanillaTerrainEnabled;
            GPurConfig.vanillaTerrainMode = this.vanillaTerrainMode;
            GPurConfig.vanillaTerrainVerifyEveryBatch = this.vanillaTerrainVerifyEveryBatch;
            GPurConfig.vanillaTerrainMinValues = this.vanillaTerrainMinValues;
            GPurConfig.vanillaTerrainMaxSlabValues = this.vanillaTerrainMaxSlabValues;
            GPurConfig.vanillaNoiseGpuEnabled = this.vanillaNoiseGpuEnabled;
            GPurConfig.vanillaAquiferGpuEnabled = this.vanillaAquiferGpuEnabled;
        }
    }
}
