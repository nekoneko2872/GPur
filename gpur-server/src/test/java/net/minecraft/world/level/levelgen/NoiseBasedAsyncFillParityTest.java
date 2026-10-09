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
import org.gpur.compute.VanillaTerrainInterpolation;
import org.gpur.compute.VanillaTerrainSlab;
import org.gpur.terrain.VanillaNoiseContinuation;
import org.junit.jupiter.api.Test;

/** Exercises the complete asynchronous vanilla block-fill continuation without a device or server. */
@VanillaFeature
public class NoiseBasedAsyncFillParityTest {
    private static final int FALLBACK_SLAB = 1;
    private static final Field ACTIVE_SLAB = field(NoiseChunk.class, "gpurSlab");
    private static final Constructor<VanillaTerrainSlab> SLAB_CONSTRUCTOR = slabConstructor();

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
        assertEquals(1, pendingSlabs.size(), "only the first X slab should be submitted before a slab CPU continuation runs");
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

    private static void completeOnSimulatedGpuThread(PendingSlab pending) throws InterruptedException {
        Thread simulatedGpu = new Thread(() -> pending.future().complete(pending.result()), "test-simulated-gpu-completion");
        simulatedGpu.start();
        simulatedGpu.join();
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

    private record PendingSlab(CompletableFuture<VanillaTerrainSlab> future, VanillaTerrainSlab result) {}

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
