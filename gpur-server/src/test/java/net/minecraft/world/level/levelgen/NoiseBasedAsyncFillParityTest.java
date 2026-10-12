package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import it.unimi.dsi.fastutil.shorts.ShortList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.compute.ComputeService;
import org.gpur.compute.VanillaAquiferBatch;
import org.gpur.compute.VanillaNoiseBatch;
import org.gpur.compute.VanillaTerrainInterpolation;
import org.gpur.compute.VanillaTerrainSlab;
import org.gpur.compute.WorldgenResult;
import org.gpur.terrain.VanillaDensityGraphBatch;
import org.gpur.terrain.VanillaNoiseContinuation;
import org.junit.jupiter.api.Test;

/** Exercises the complete asynchronous vanilla block-fill continuation without a device or server. */
@VanillaFeature
public class NoiseBasedAsyncFillParityTest {
    private static final int FALLBACK_SLAB = 1;
    private static final Field ACTIVE_SLAB = field(NoiseChunk.class, "gpurSlab");
    private static final Field ACTIVE_CORNER_GRAPHS = field(NoiseChunk.class, "gpurCornerGraphs");
    private static final Constructor<VanillaTerrainSlab> SLAB_CONSTRUCTOR = slabConstructor();
    private static final Constructor<WorldgenResult> WORLDGEN_RESULT_CONSTRUCTOR = worldgenResultConstructor();

    @Test
    void delayedSlabsResumeOnCpuExecutorAndMatchVanillaFill() throws Exception {
        WorldgenFixture fixture = worldgenFixture();
        compareChunk(-0x1234_5678_9abc_defL, -2, -3, false, fixture);
        // Both chunk edges remain inside the world border at this coordinate.
        compareChunk(0xfedc_ba98_7654_3210L, 1_874_998, -1_874_999, true, fixture);
    }

    private static void compareChunk(long seed, int chunkX, int chunkZ, boolean includeFallback, WorldgenFixture fixture) throws Exception {
        NoiseGeneratorSettings settings = smallOverworldSettings(fixture.overworldSettings());
        Holder<NoiseGeneratorSettings> settingsHolder = Holder.direct(settings);
        BiomeSource biomeSource = new FixedBiomeSource(fixture.plains());
        NoiseBasedChunkGenerator vanillaGenerator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
        NoiseBasedChunkGenerator asyncGenerator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
        RandomState vanillaRandomState = RandomState.create(settings, fixture.noiseLookup(), seed);
        RandomState asyncRandomState = RandomState.create(settings, fixture.noiseLookup(), seed);
        StructureManager structureManager = emptyStructureManager();
        ProtoChunk vanillaChunk = protoChunk(chunkX, chunkZ, fixture);
        ProtoChunk asyncChunk = protoChunk(chunkX, chunkZ, fixture);

        assertSame(vanillaChunk, vanillaGenerator.fillFromNoise(Blender.empty(), vanillaRandomState, structureManager, vanillaChunk).join());

        ComputeService service = mock(ComputeService.class);
        when(service.tryAcquireVanillaContinuation()).thenReturn(true);
        ManualExecutor cpuExecutor = new ManualExecutor();
        List<PendingSlab> pendingSlabs = new ArrayList<>();
        AtomicInteger slabIndex = new AtomicInteger();
        doAnswer(invocation -> {
            int index = slabIndex.getAndIncrement();
            int width = invocation.getArgument(0);
            int height = invocation.getArgument(1);
            int cellsY = invocation.getArgument(2);
            double[][][] slice0 = invocation.getArgument(3);
            double[][][] slice1 = invocation.getArgument(4);
            assertSame(cpuExecutor, invocation.getArgument(5), "the async service must receive the CPU continuation executor");

            int[] encoded = VanillaTerrainInterpolation.input(width, height, cellsY, slice0, slice1);
            VanillaTerrainSlab result = index == FALLBACK_SLAB && includeFallback
                ? null
                : SLAB_CONSTRUCTOR.newInstance(VanillaTerrainInterpolation.reference(encoded), width, height, cellsY);
            CompletableFuture<VanillaTerrainSlab> future = new CompletableFuture<>();
            pendingSlabs.add(new PendingSlab(future, result));
            return future;
        }).when(service).prepareVanillaSlabAsync(
            anyInt(), anyInt(), anyInt(), any(double[][][].class), any(double[][][].class), any(Executor.class)
        );

        CompletableFuture<ChunkAccess> fillFuture;
        try (VanillaNoiseContinuation continuation = VanillaNoiseContinuation.enter(service, cpuExecutor)) {
            fillFuture = asyncGenerator.fillFromNoise(Blender.empty(), asyncRandomState, structureManager, asyncChunk);
            assertTrue(continuation.used(), "the standard NoiseBasedChunkGenerator should use the async fill route");
        }

        assertFalse(fillFuture.isDone(), "the status future must remain pending while the first slab is pending");
        assertEquals(0, pendingSlabs.size(), "the fill first waits for its numeric ranking/static-noise preflight");
        assertEquals(1, cpuExecutor.size(), "preflight installation should be serialized on the CPU continuation executor");
        assertAllAir(asyncChunk, chunkX, chunkZ, "before the first simulated GPU completion");
        cpuExecutor.runNext();
        assertEquals(1, pendingSlabs.size(), "the all-of preflight should enqueue one serialized owner continuation");
        assertAllAir(asyncChunk, chunkX, chunkZ, "after preflight and before the first simulated GPU completion");

        int nextSlab = 0;
        while (!fillFuture.isDone()) {
            assertTrue(nextSlab < pendingSlabs.size(), "a pending async stage should own the next slab future");
            PendingSlab pending = pendingSlabs.get(nextSlab++);
            BlockState[] blocksBeforeCompletion = snapshotBlocks(asyncChunk, chunkX, chunkZ);
            completeOnSimulatedGpuThread(pending);

            assertFalse(fillFuture.isDone(), "completing a slab must not finish the chunk before its CPU continuation runs");
            assertEquals(1, cpuExecutor.size(), "GPU completion should enqueue exactly one CPU continuation");
            assertBlockSnapshot(asyncChunk, chunkX, chunkZ, blocksBeforeCompletion,
                "after GPU completion but before draining the CPU executor");

            cpuExecutor.runNext();
            if (!fillFuture.isDone()) {
                assertTrue(pendingSlabs.size() > nextSlab, "the CPU continuation should submit the next slab in order");
            }
        }

        assertSame(asyncChunk, fillFuture.join());
        assertEquals(4, pendingSlabs.size(), "every X slab should be processed once");
        assertEquals(includeFallback, pendingSlabs.get(FALLBACK_SLAB).result() == null, "the requested CPU fallback slab should be exercised");
        assertChunksEqual(vanillaChunk, asyncChunk, chunkX, chunkZ);

        NoiseChunk noiseChunk = asyncChunk.getOrCreateNoiseChunk(ignored -> fail("the async fill should retain its NoiseChunk"));
        assertNull(ACTIVE_SLAB.get(noiseChunk), "the active slab must be cleared after fill completion");
    }

    @Test
    void generatorAdoptsExactPreparedCornerFramesAndMatchesVanilla() throws Exception {
        WorldgenFixture fixture = worldgenFixture();
        NoiseGeneratorSettings settings = smallOverworldSettings(fixture.overworldSettings());
        Holder<NoiseGeneratorSettings> settingsHolder = Holder.direct(settings);
        BiomeSource biomeSource = new FixedBiomeSource(fixture.plains());
        NoiseBasedChunkGenerator vanillaGenerator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
        NoiseBasedChunkGenerator asyncGenerator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
        long seed = 0x1462_26L;
        int chunkX = -11;
        int chunkZ = 17;
        RandomState vanillaRandomState = RandomState.create(settings, fixture.noiseLookup(), seed);
        RandomState asyncRandomState = RandomState.create(settings, fixture.noiseLookup(), seed);
        StructureManager structureManager = emptyStructureManager();
        ProtoChunk vanillaChunk = protoChunk(chunkX, chunkZ, fixture);
        ProtoChunk asyncChunk = protoChunk(chunkX, chunkZ, fixture);
        assertSame(vanillaChunk, vanillaGenerator.fillFromNoise(Blender.empty(), vanillaRandomState, structureManager, vanillaChunk).join());

        ComputeService service = mock(ComputeService.class);
        when(service.tryAcquireVanillaContinuation()).thenReturn(true);
        when(service.worldgenEligible(anyInt())).thenAnswer(invocation ->
            invocation.<Integer>getArgument(0) == VanillaDensityGraphBatch.WORKLOAD
        );
        ManualExecutor cpuExecutor = new ManualExecutor();
        List<PendingWorldgen> pendingGraphs = new ArrayList<>();
        AtomicInteger closedGraphResults = new AtomicInteger();
        AtomicLong consumedGraphValues = new AtomicLong();
        doAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            assertEquals(VanillaDensityGraphBatch.WORKLOAD, input[0]);
            assertSame(cpuExecutor, invocation.getArgument(1), "W7 must use the serialized CPU continuation executor");
            PendingWorldgen pending = new PendingWorldgen(input, 0L, new NonCancellingFuture<>(), closedGraphResults, consumedGraphValues);
            pendingGraphs.add(pending);
            return pending.future();
        }).when(service).prepareWorldgenAsync(any(int[].class), any(Executor.class));
        configureCompletedSlabs(service);

        CompletableFuture<ChunkAccess> fillFuture;
        try (VanillaNoiseContinuation continuation = VanillaNoiseContinuation.enter(service, cpuExecutor)) {
            fillFuture = asyncGenerator.fillFromNoise(Blender.empty(), asyncRandomState, structureManager, asyncChunk);
            assertTrue(continuation.used(), "the standard generator should enter the asynchronous owner path");
        }

        assertFalse(fillFuture.isDone(), "the generator waits for the complete W7 root set");
        assertTrue(pendingGraphs.size() >= 8, "W7 should submit every original corner root");
        assertEquals(0, cpuExecutor.size(), "the single owner preflight waits for all W7 requests");
        assertAllAir(asyncChunk, chunkX, chunkZ, "before the W7 results are ready");

        completeWorldgenRequestsOnSimulatedGpuThread(pendingGraphs);
        assertFalse(fillFuture.isDone(), "GPU completion must not run chunk mutation inline");
        assertEquals(1, cpuExecutor.size(), "the completed aggregate must queue its CPU owner continuation");
        assertAllAir(asyncChunk, chunkX, chunkZ, "after W7 completion and before CPU adoption");
        cpuExecutor.runNext();

        assertTrue(fillFuture.isDone());
        assertSame(asyncChunk, fillFuture.join());
        assertChunksEqual(vanillaChunk, asyncChunk, chunkX, chunkZ);
        assertEquals(pendingGraphs.size(), closedGraphResults.get(), "the adopted aggregate releases every W7 result after fill");
        assertEquals(3_400L, consumedGraphValues.get(), "complete eight-root corner frames must supply all small-height lattice values");
        NoiseChunk noiseChunk = asyncChunk.getOrCreateNoiseChunk(ignored -> fail("the async fill should retain its NoiseChunk"));
        assertNull(ACTIVE_CORNER_GRAPHS.get(noiseChunk), "the complete graph lease must close after interpolation");
        assertNull(ACTIVE_SLAB.get(noiseChunk), "the interpolation slab must close after fill");
    }

    @Test
    void cancellingGeneratorFillCancelsW5W6W7SourcesAndClosesCompletedAndLateResults() throws Exception {
        boolean priorAquiferGpuEnabled = org.gpur.GPurConfig.vanillaAquiferGpuEnabled;
        org.gpur.GPurConfig.vanillaAquiferGpuEnabled = true;
        try {
            WorldgenFixture fixture = worldgenFixture();
            NoiseGeneratorSettings settings = smallOverworldSettings(fixture.overworldSettings());
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(
                new FixedBiomeSource(fixture.plains()), Holder.direct(settings)
            );
            int chunkX = 4;
            int chunkZ = -9;
            RandomState randomState = RandomState.create(settings, fixture.noiseLookup(), 0x7c_26L);
            ProtoChunk chunk = protoChunk(chunkX, chunkZ, fixture);
            ComputeService service = mock(ComputeService.class);
            when(service.tryAcquireVanillaContinuation()).thenReturn(true);
            when(service.worldgenEligible(anyInt())).thenAnswer(invocation -> {
                int workload = invocation.getArgument(0);
                return workload == VanillaNoiseBatch.WORKLOAD
                    || workload == VanillaAquiferBatch.WORKLOAD
                    || workload == VanillaDensityGraphBatch.WORKLOAD;
            });
            ManualExecutor cpuExecutor = new ManualExecutor();
            List<PendingWorldgen> pendingWorldgen = new ArrayList<>();
            AtomicInteger closedResults = new AtomicInteger();
            AtomicLong consumedValues = new AtomicLong();
            doAnswer(invocation -> {
                int[] input = invocation.getArgument(0);
                assertSame(cpuExecutor, invocation.getArgument(1));
                assertNotEquals(VanillaNoiseBatch.WORKLOAD, input[0], "W5 must use the owner-byte-accounted overload");
                PendingWorldgen pending = new PendingWorldgen(input, 0L, new NonCancellingFuture<>(), closedResults, consumedValues);
                pendingWorldgen.add(pending);
                return pending.future();
            }).when(service).prepareWorldgenAsync(any(int[].class), any(Executor.class));
            doAnswer(invocation -> {
                int[] input = invocation.getArgument(0);
                long ownerBytes = invocation.getArgument(2);
                assertEquals(VanillaNoiseBatch.WORKLOAD, input[0], "the three-argument service call is the W5 static-noise cache");
                assertTrue(ownerBytes > 0L, "W5 must account for its retained cache before dispatch");
                assertSame(cpuExecutor, invocation.getArgument(1));
                PendingWorldgen pending = new PendingWorldgen(input, ownerBytes, new NonCancellingFuture<>(), closedResults, consumedValues);
                pendingWorldgen.add(pending);
                return pending.future();
            }).when(service).prepareWorldgenAsync(any(int[].class), any(Executor.class), anyLong());

            CompletableFuture<ChunkAccess> fillFuture;
            try (VanillaNoiseContinuation continuation = VanillaNoiseContinuation.enter(service, cpuExecutor)) {
                fillFuture = generator.fillFromNoise(Blender.empty(), randomState, emptyStructureManager(), chunk);
                assertTrue(continuation.used());
            }
            List<PendingWorldgen> staticNoise = pendingWorldgen.stream()
                .filter(pending -> pending.input()[0] == VanillaNoiseBatch.WORKLOAD).toList();
            List<PendingWorldgen> aquifer = pendingWorldgen.stream()
                .filter(pending -> pending.input()[0] == VanillaAquiferBatch.WORKLOAD).toList();
            List<PendingWorldgen> cornerGraphs = pendingWorldgen.stream()
                .filter(pending -> pending.input()[0] == VanillaDensityGraphBatch.WORKLOAD).toList();
            assertFalse(staticNoise.isEmpty(), "W5 static-noise preparation should be pending before cancellation");
            assertEquals(1, aquifer.size(), "W6 aquifer ranking should be pending before cancellation");
            assertTrue(cornerGraphs.size() >= 8, "W7 should prepare every corner root before cancellation");
            assertEquals(pendingWorldgen.size(), staticNoise.size() + aquifer.size() + cornerGraphs.size());
            assertTrue(staticNoise.stream().allMatch(pending -> pending.ownerBytes() > 0L),
                "every W5 source must use the owner-byte-accounted service overload");
            assertEquals(0, cpuExecutor.size(), "all three preparation stages remain held by their source futures");

            completeWorldgenRequestOnSimulatedGpuThread(cornerGraphs.get(0));
            assertTrue(cornerGraphs.get(0).future().isDone());
            assertEquals(0, closedResults.get(), "the partial W7 aggregate retains its result until cancellation");
            assertAllAir(chunk, chunkX, chunkZ, "before cancelling the pending generator fill");
            BlockState[] beforeCancellation = snapshotBlocks(chunk, chunkX, chunkZ);
            int[] heightsBeforeCancellation = snapshotHeightmaps(chunk);
            List<List<Short>> fluidFlagsBeforeCancellation = postProcessing(chunk);
            assertTrue(fillFuture.cancel(false), "the outer chunk-fill future should accept cancellation");
            assertTrue(fillFuture.isCancelled());
            assertEquals(1, closedResults.get(), "cancellation closes the completed partial W7 result");
            assertEquals(0L, consumedValues.get(), "cancelled preparations supply no numeric values");
            for (PendingWorldgen pending : pendingWorldgen) {
                if (pending != cornerGraphs.get(0)) {
                    assertTrue(pending.future().cancellationCalls() > 0,
                        "outer cancellation must reach each held W5, W6, and W7 numeric source");
                }
            }

            for (PendingWorldgen pending : staticNoise) completeWorldgenRequestOnSimulatedGpuThread(pending);
            assertEquals(0, cpuExecutor.size(), "late W5 completion closes through the already-cancelled cache owner");
            for (PendingWorldgen pending : aquifer) completeWorldgenRequestOnSimulatedGpuThread(pending);
            assertEquals(0, cpuExecutor.size(),
                "the cancelled all-of continuation must not enqueue an owner task when late W6 completes the preparation set");
            for (PendingWorldgen pending : cornerGraphs) {
                if (pending != cornerGraphs.get(0)) completeWorldgenRequestOnSimulatedGpuThread(pending);
            }
            assertEquals(pendingWorldgen.size(), closedResults.get(), "late numeric results close instead of being adopted");
            assertEquals(0L, consumedValues.get(), "late results remain unconsumed after cancellation");
            assertEquals(0, cpuExecutor.size(), "late W7 completions must not queue chunk work after cancellation");
            assertTrue(fillFuture.isCancelled(), "draining the already-registered join cannot revive the owner future");
            verify(service, never()).prepareVanillaSlabAsync(
                anyInt(), anyInt(), anyInt(), any(double[][][].class), any(double[][][].class), any(Executor.class)
            );
            assertBlockSnapshot(chunk, chunkX, chunkZ, beforeCancellation, "after cancellation and late W5/W6/W7 completions");
            assertArrayEquals(heightsBeforeCancellation, snapshotHeightmaps(chunk), "heightmaps must stay unchanged after cancellation");
            assertEquals(fluidFlagsBeforeCancellation, postProcessing(chunk), "fluid post-processing flags must stay unchanged after cancellation");
            NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(ignored -> fail("the async fill should retain its NoiseChunk"));
            assertNull(ACTIVE_CORNER_GRAPHS.get(noiseChunk), "cancelled graph ownership must not remain installed");
        } finally {
            org.gpur.GPurConfig.vanillaAquiferGpuEnabled = priorAquiferGpuEnabled;
        }
    }

    private static void completeOnSimulatedGpuThread(PendingSlab pending) throws InterruptedException {
        Thread simulatedGpu = new Thread(() -> pending.future().complete(pending.result()), "test-simulated-gpu-completion");
        simulatedGpu.start();
        simulatedGpu.join();
    }

    private static void configureCompletedSlabs(ComputeService service) throws Exception {
        doAnswer(invocation -> {
            int width = invocation.getArgument(0);
            int height = invocation.getArgument(1);
            int cellsY = invocation.getArgument(2);
            double[][][] slice0 = invocation.getArgument(3);
            double[][][] slice1 = invocation.getArgument(4);
            int[] encoded = VanillaTerrainInterpolation.input(width, height, cellsY, slice0, slice1);
            VanillaTerrainSlab slab = SLAB_CONSTRUCTOR.newInstance(
                VanillaTerrainInterpolation.reference(encoded), width, height, cellsY
            );
            return CompletableFuture.completedFuture(slab);
        }).when(service).prepareVanillaSlabAsync(
            anyInt(), anyInt(), anyInt(), any(double[][][].class), any(double[][][].class), any(Executor.class)
        );
    }

    private static void completeWorldgenRequestsOnSimulatedGpuThread(List<PendingWorldgen> requests) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread simulatedGpu = new Thread(() -> {
            try {
                for (PendingWorldgen request : requests) request.complete();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "test-simulated-gpu-completion");
        simulatedGpu.start();
        simulatedGpu.join();
        if (failure.get() != null) throw new AssertionError("simulated W7 completion failed", failure.get());
    }

    private static void completeWorldgenRequestOnSimulatedGpuThread(PendingWorldgen request) throws Exception {
        completeWorldgenRequestsOnSimulatedGpuThread(List.of(request));
    }

    private static WorldgenResult worldgenResult(int[] output, AtomicInteger closed, AtomicLong consumed) throws Exception {
        BooleanSupplier valid = () -> true;
        Runnable release = closed::incrementAndGet;
        LongConsumer consumption = consumed::addAndGet;
        return WORLDGEN_RESULT_CONSTRUCTOR.newInstance(output, valid, release, consumption);
    }

    private static void assertChunksEqual(ChunkAccess expected, ChunkAccess actual, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int y = -64; y < 64; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    position.set(minX + x, y, minZ + z);
                    BlockState expectedState = expected.getBlockState(position);
                    BlockState actualState = actual.getBlockState(position);
                    assertEquals(expectedState, actualState, "block mismatch at " + position);
                }
            }
        }

        for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
            Heightmap expectedMap = expected.getOrCreateHeightmapUnprimed(type);
            Heightmap actualMap = actual.getOrCreateHeightmapUnprimed(type);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(expectedMap.getFirstAvailable(x, z), actualMap.getFirstAvailable(x, z),
                        type + " height mismatch at local " + x + "," + z);
                }
            }
        }

        assertEquals(postProcessing(expected), postProcessing(actual), "post-processing fluid flags differ");
    }

    private static List<List<Short>> postProcessing(ChunkAccess chunk) {
        List<List<Short>> snapshot = new ArrayList<>();
        for (ShortList section : chunk.getPostProcessing()) {
            if (section == null) {
                snapshot.add(null);
                continue;
            }
            List<Short> flags = new ArrayList<>(section.size());
            for (short flag : section) flags.add(flag);
            snapshot.add(List.copyOf(flags));
        }
        return snapshot;
    }

    private static int[] snapshotHeightmaps(ChunkAccess chunk) {
        int[] values = new int[2 * 16 * 16];
        int offset = 0;
        for (Heightmap.Types type : List.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG)) {
            Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) values[offset++] = heightmap.getFirstAvailable(x, z);
            }
        }
        return values;
    }

    private static void assertAllAir(ChunkAccess chunk, int chunkX, int chunkZ, String when) {
        BlockState[] air = new BlockState[16 * 16 * 128];
        java.util.Arrays.fill(air, Blocks.AIR.defaultBlockState());
        assertBlockSnapshot(chunk, chunkX, chunkZ, air, when);
    }

    private static BlockState[] snapshotBlocks(ChunkAccess chunk, int chunkX, int chunkZ) {
        BlockState[] snapshot = new BlockState[16 * 16 * 128];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int y = -64; y < 64; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    position.set((chunkX << 4) + x, y, (chunkZ << 4) + z);
                    snapshot[index++] = chunk.getBlockState(position);
                }
            }
        }
        return snapshot;
    }

    private static void assertBlockSnapshot(ChunkAccess chunk, int chunkX, int chunkZ, BlockState[] expected, String when) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int y = -64; y < 64; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    position.set((chunkX << 4) + x, y, (chunkZ << 4) + z);
                    assertEquals(expected[index++], chunk.getBlockState(position), "unexpected write " + when + " at " + position);
                }
            }
        }
    }

    private static ProtoChunk protoChunk(int chunkX, int chunkZ, WorldgenFixture fixture) {
        return new ProtoChunk(
            new ChunkPos(chunkX, chunkZ),
            UpgradeData.EMPTY,
            LevelHeightAccessor.create(-64, 128),
            fixture.palettedContainerFactory(),
            null
        );
    }

    private static StructureManager emptyStructureManager() {
        StructureManager manager = mock(StructureManager.class);
        doReturn(List.of()).when(manager).startsForStructure(any(ChunkPos.class), any());
        return manager;
    }

    private static NoiseGeneratorSettings smallOverworldSettings(NoiseGeneratorSettings original) {
        NoiseSettings size = NoiseSettings.create(-64, 128,
            original.noiseSettings().noiseSizeHorizontal(), original.noiseSettings().noiseSizeVertical());
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
        return new WorldgenFixture(
            settings,
            noises,
            registries.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS),
            PalettedContainerFactory.create(registries)
        );
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

    @SuppressWarnings("unchecked")
    private static Constructor<VanillaTerrainSlab> slabConstructor() {
        try {
            Constructor<?> constructor = VanillaTerrainSlab.class.getDeclaredConstructor(int[].class, int.class, int.class, int.class);
            constructor.setAccessible(true);
            return (Constructor<VanillaTerrainSlab>)constructor;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Constructor<WorldgenResult> worldgenResultConstructor() {
        try {
            Constructor<?> constructor = WorldgenResult.class.getDeclaredConstructor(
                int[].class, java.util.function.BooleanSupplier.class, Runnable.class, java.util.function.LongConsumer.class
            );
            constructor.setAccessible(true);
            return (Constructor<WorldgenResult>)constructor;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record PendingSlab(CompletableFuture<VanillaTerrainSlab> future, VanillaTerrainSlab result) {}

    private record PendingWorldgen(
        int[] input, long ownerBytes, NonCancellingFuture<WorldgenResult> future, AtomicInteger closed, AtomicLong consumed
    ) {
        private void complete() throws Exception {
            int[] output = switch (this.input[0]) {
                case VanillaNoiseBatch.WORKLOAD -> VanillaNoiseBatch.reference(this.input);
                case VanillaAquiferBatch.WORKLOAD -> VanillaAquiferBatch.reference(this.input);
                case VanillaDensityGraphBatch.WORKLOAD -> VanillaDensityGraphBatch.reference(this.input);
                default -> throw new IllegalArgumentException("unexpected worldgen workload " + this.input[0]);
            };
            WorldgenResult result = worldgenResult(output, this.closed, this.consumed);
            if (!this.future.complete(result)) result.close();
        }
    }

    private static final class NonCancellingFuture<T> extends CompletableFuture<T> {
        private final AtomicInteger cancellationCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancellationCalls.incrementAndGet();
            return false;
        }

        private int cancellationCalls() {
            return this.cancellationCalls.get();
        }
    }

    private record WorldgenFixture(
        NoiseGeneratorSettings overworldSettings,
        HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noiseLookup,
        Holder<net.minecraft.world.level.biome.Biome> plains,
        PalettedContainerFactory palettedContainerFactory
    ) {}

    private static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            this.queue.add(command);
        }

        int size() {
            return this.queue.size();
        }

        void runNext() {
            Runnable next = this.queue.poll();
            assertNotNull(next, "no CPU continuation was queued");
            next.run();
        }
    }
}
