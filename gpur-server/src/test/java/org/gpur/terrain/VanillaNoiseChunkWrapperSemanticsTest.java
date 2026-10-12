package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

/**
 * Independent vanilla runtime-wrapper contract for future graph lowering. These tests use the
 * actual NoiseChunk caches; neither graph snapshots nor GPU results replace their computation.
 */
@VanillaFeature
class VanillaNoiseChunkWrapperSemanticsTest {
    private static final Method WRAP = method(NoiseChunk.class, "wrap", DensityFunction.class);

    @Test
    void flatCacheUsesQuartAlignedYZeroInsideItsTableAndOriginalContextOutside() throws Exception {
        DensityFunction source = DensityFunctions.yClampedGradient(-100, 100, -100.0, 100.0);
        for (int[] origin : new int[][] {{0, 0}, {-32, -48}}) {
            NoiseChunk owner = fixture(origin[0], origin[1]);
            DensityFunction cache = wrap(owner, DensityFunctions.flatCache(source));
            Field values = field(cache.getClass(), "values");
            Field size = field(cache.getClass(), "sizeXZ");
            int sizeXZ = size.getInt(cache);
            double[] table = (double[])values.get(cache);

            assertEquals(5, sizeXZ, "ordinary 16-block Overworld table includes its far corner");
            for (double entry : table) assertRawEquals(source.compute(new DensityFunction.SinglePointContext(0, 0, 0)), entry);
            assertRawEquals(0.0, cache.compute(new DensityFunction.SinglePointContext(origin[0] + 1, 73, origin[1] + 3)));

            // Give each existing table entry a distinct value. This observes the vanilla table
            // index, including negative floor-to-quart behavior, independently of its filler.
            for (int i = 0; i < table.length; i++) table[i] = 1000.0 + i;
            VanillaDensityGraph.WrapperBindings captured = owner.gpurSnapshotCornerGraphBindings();
            assertNotNull(captured);
            assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(cache));
            VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(cache, VanillaDensityGraph.Limits.DEFAULT, captured);
            for (int[] offset : new int[][] {{0, 0}, {1, 3}, {3, 1}, {4, 7}, {16, 16}, {19, 19}}) {
                int x = origin[0] + offset[0];
                int z = origin[1] + offset[1];
                int quartX = Math.floorDiv(x, 4) - Math.floorDiv(origin[0], 4);
                int quartZ = Math.floorDiv(z, 4) - Math.floorDiv(origin[1], 4);
                double expected = table[quartX + quartZ * sizeXZ];
                assertRawEquals(expected, cache.compute(new DensityFunction.SinglePointContext(x, 73, z)));
                assertRawEquals(expected, cache.compute(new DensityFunction.SinglePointContext(x, -31, z)));
                assertRawEquals(expected, snapshot.evaluate(x, 73, z));
                int[] wire = VanillaDensityGraphBatch.input(snapshot, new VanillaDensityGraphBatch.Sample(x, -31, z));
                assertRawEquals(expected, VanillaDensityGraphBatch.outputValue(VanillaDensityGraphBatch.reference(wire), 0));
            }

            for (int[] offset : new int[][] {{-1, 0}, {20, 0}, {0, -1}, {0, 20}}) {
                DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(
                    origin[0] + offset[0], 73, origin[1] + offset[1]
                );
                assertRawEquals(source.compute(context), cache.compute(context));
                assertThrows(IllegalArgumentException.class, () -> snapshot.evaluate(context.blockX(), context.blockY(), context.blockZ()));
                assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.input(snapshot,
                    new VanillaDensityGraphBatch.Sample(context.blockX(), context.blockY(), context.blockZ())));
                assertNotEquals(Double.doubleToRawLongBits(source.compute(new DensityFunction.SinglePointContext(
                    Math.floorDiv(context.blockX(), 4) * 4, 0, Math.floorDiv(context.blockZ(), 4) * 4
                ))), Double.doubleToRawLongBits(cache.compute(context)), "outside table retains the original Y context");
            }
            table[0] = Double.NaN;
            assertRawEquals(1000.0, snapshot.evaluate(origin[0], 73, origin[1]));
            assertRawEquals(1000.0, VanillaDensityGraph.snapshot(cache, VanillaDensityGraph.Limits.DEFAULT, captured)
                .evaluate(origin[0], 73, origin[1]));
            owner.initializeForFirstCellX();
            try {
                assertNull(owner.gpurSnapshotCornerGraphBindings(), "capture is limited to preparation before interpolation");
            } finally {
                owner.stopInterpolation();
            }
        }
    }

    @Test
    void cache2DIsNotTransparentForAYDependentFunctionAndFillArrayDoesNotUseItsScalarMemo() throws Exception {
        NoiseChunk owner = fixture(-16, 32);
        DensityFunction source = DensityFunctions.yClampedGradient(0, 100, 0.0, 100.0);
        DensityFunction cache = wrap(owner, DensityFunctions.cache2d(source));
        assertRawEquals(10.0, cache.compute(new DensityFunction.SinglePointContext(-11, 10, 35)));
        assertRawEquals(10.0, cache.compute(new DensityFunction.SinglePointContext(-11, 20, 35)));
        assertRawEquals(20.0, source.compute(new DensityFunction.SinglePointContext(-11, 20, 35)));

        double[] output = new double[3];
        cache.fillArray(output, contexts(-11, 35, 10, 20, 30));
        assertRawEquals(10.0, output[0]);
        assertRawEquals(20.0, output[1]);
        assertRawEquals(30.0, output[2]);
        assertRawEquals(10.0, cache.compute(new DensityFunction.SinglePointContext(-11, 30, 35)));
    }

    @Test
    void cacheOnceKeepsArrayEpochAndOwnerIdentitySeparateFromScalarCoordinates() throws Exception {
        NoiseChunk owner = fixture(0, 0);
        DensityFunction source = DensityFunctions.yClampedGradient(0, 100, 0.0, 100.0);
        DensityFunction cache = wrap(owner, DensityFunctions.cacheOnce(source));
        Field arrayEpoch = field(NoiseChunk.class, "arrayInterpolationCounter");
        arrayEpoch.setLong(owner, 11L);
        double[] initial = new double[2];
        cache.fillArray(initial, contexts(0, 0, 10, 20));

        setOwnerContext(owner, 99, 1);
        assertRawEquals(20.0, cache.compute(owner));
        assertRawEquals(99.0, cache.compute(new DensityFunction.SinglePointContext(0, 99, 0)));

        double[] sameEpoch = new double[2];
        cache.fillArray(sameEpoch, contexts(0, 0, 40, 50));
        assertRawEquals(10.0, sameEpoch[0]);
        assertRawEquals(20.0, sameEpoch[1]);
        arrayEpoch.setLong(owner, 12L);
        double[] nextEpoch = new double[2];
        cache.fillArray(nextEpoch, contexts(0, 0, 40, 50));
        assertRawEquals(40.0, nextEpoch[0]);
        assertRawEquals(50.0, nextEpoch[1]);
        assertRawEquals(50.0, cache.compute(owner));
    }

    @Test
    void preparationRejectsANonemptyBlenderBeforeReadingWrapperBindings() throws Exception {
        NoiseChunk owner = fixture(0, 0);
        Long2ObjectOpenHashMap<BlendingData> data = new Long2ObjectOpenHashMap<>();
        data.put(0L, BlendingData.unpack(new BlendingData.Packed(0, 16, Optional.empty())));
        var constructor = Blender.class.getDeclaredConstructor(Long2ObjectOpenHashMap.class, Long2ObjectOpenHashMap.class);
        constructor.setAccessible(true);
        Blender activeBlender = constructor.newInstance(data, new Long2ObjectOpenHashMap<BlendingData>());
        // Replace only the test owner's field to exercise the early guard. This is not a test of
        // full old/new terrain blending or a claim that such terrain can use the GPU graph.
        field(NoiseChunk.class, "blender").set(owner, activeBlender);
        assertNull(owner.gpurSnapshotCornerGraphBindings());
    }

    private static DensityFunction.ContextProvider contexts(final int x, final int z, final int... ys) {
        return new DensityFunction.ContextProvider() {
            @Override
            public DensityFunction.FunctionContext forIndex(final int index) {
                return new DensityFunction.SinglePointContext(x, ys[index], z);
            }

            @Override
            public void fillAllDirectly(final double[] output, final DensityFunction function) {
                assertEquals(ys.length, output.length);
                for (int i = 0; i < output.length; i++) output[i] = function.compute(this.forIndex(i));
            }
        };
    }

    private static DensityFunction wrap(final NoiseChunk owner, final DensityFunction function) throws Exception {
        return (DensityFunction)WRAP.invoke(owner, function);
    }

    private static void setOwnerContext(final NoiseChunk owner, final int y, final int arrayIndex) throws Exception {
        field(NoiseChunk.class, "cellStartBlockY").setInt(owner, y);
        field(NoiseChunk.class, "inCellY").setInt(owner, 0);
        field(NoiseChunk.class, "arrayIndex").setInt(owner, arrayIndex);
    }

    private static NoiseChunk fixture(final int minX, final int minZ) {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState randomState = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), 1196459378L);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        return new NoiseChunk(16 / settings.noiseSettings().getCellWidth(), randomState, minX, minZ,
            settings.noiseSettings(), EmptyBeardifier.INSTANCE, settings, picker, Blender.empty());
    }

    private static void assertRawEquals(final double expected, final double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static Field field(final Class<?> owner, final String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method method(final Class<?> owner, final String name, final Class<?>... arguments) {
        try {
            Method method = owner.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private enum EmptyBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(final DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
    }
}
