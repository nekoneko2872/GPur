package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

/**
 * Source-audit fixture for the runtime cache effects of future corner-graph adoption.
 *
 * <p>The copied corners come from an untouched CPU NoiseChunk, not a GPU or the graph exporter.
 * No production root evaluation is bypassed by this test. Passing this fixture alone cannot
 * authorize GPU adoption: graph/root parity and lifecycle integration remain separate gates.
 */
@VanillaFeature
class VanillaDensitySliceCacheAuditTest {
    private static final Field INTERPOLATORS = field(NoiseChunk.class, "interpolators");
    private static final Field WRAPPED = field(NoiseChunk.class, "wrapped");
    private static final Field CELL_CACHES = field(NoiseChunk.class, "cellCaches");
    private static final Field INTERPOLATING = field(NoiseChunk.class, "interpolating");
    private static final Field ARRAY_COUNTER = field(NoiseChunk.class, "arrayInterpolationCounter");
    private static final Field INTERPOLATION_COUNTER = field(NoiseChunk.class, "interpolationCounter");
    private static final Method DENSITY = method(NoiseChunk.class, "getInterpolatedDensity");
    private static final Method BLOCK_STATE = method(NoiseChunk.class, "getInterpolatedState");

    @Test
    void copiedCpuCornersAndInvalidatedMemoStateMatchFullOverworldConsumption() throws Exception {
        // Exercise both signs, all eight interpolators, first selectCellYZ, all cell boundaries,
        // aquifer/ore decisions and the shared runtime caches in the regular vanilla router.
        for (int[] origin : new int[][] {{0, 0}, {-32, -48}}) {
            compareChunk(origin[0], origin[1], true, true);
        }
    }

    @Test
    void untouchedCpuFixturesAgreeBeforeTestingTheCornerSubstitution() throws Exception {
        compareChunk(-16, 32, false, false);
    }

    @Test
    void unchangedScalarMemoEpochRequiresInvalidationWhenTheSampleContextChanges() throws Exception {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        NoiseChunk chunk = fixture(registries, settings, 0, 0);
        DensityFunction source = DensityFunctions.yClampedGradient(0, 100, 0.0, 100.0);
        Method wrap = NoiseChunk.class.getDeclaredMethod("wrap", DensityFunction.class);
        wrap.setAccessible(true);
        DensityFunction cached = (DensityFunction)wrap.invoke(chunk, DensityFunctions.cacheOnce(source));

        // A skipped CPU fill can leave a scalar memo at an unchanged epoch. Test this actual
        // CacheOnce behavior explicitly rather than assuming the ordinary Overworld fixture
        // necessarily exposes it: that fixture also passed without invalidation in attempt 3.
        INTERPOLATION_COUNTER.setLong(chunk, 7L);
        restoreSliceContext(chunk, 0, 0, 10, 0);
        assertEquals(Double.doubleToRawLongBits(source.compute(chunk)),
            Double.doubleToRawLongBits(cached.compute(chunk)));
        restoreSliceContext(chunk, 0, 0, 20, 0);
        assertEquals(Double.doubleToRawLongBits(10.0), Double.doubleToRawLongBits(cached.compute(chunk)),
            "same scalar memo epoch retains the old context value");
        assertEquals(Double.doubleToRawLongBits(20.0), Double.doubleToRawLongBits(source.compute(chunk)));
        assertTrue(invalidateMemoState(chunk) > 0);
        assertEquals(Double.doubleToRawLongBits(source.compute(chunk)),
            Double.doubleToRawLongBits(cached.compute(chunk)), "invalidated memo recomputes the new context");
    }

    private static void compareChunk(int chunkMinX, int chunkMinZ, boolean copiedCorners, boolean invalidateMemo) throws Exception {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        NoiseChunk expected = fixture(registries, settings, chunkMinX, chunkMinZ);
        NoiseChunk actual = fixture(registries, settings, chunkMinX, chunkMinZ);
        int cellWidth = settings.noiseSettings().getCellWidth();
        int cellHeight = settings.noiseSettings().getCellHeight();
        int cellCountXZ = 16 / cellWidth;
        int cellCountY = settings.noiseSettings().height() / cellHeight;
        int minY = settings.noiseSettings().minY();
        int sliceEpochs = cellCountXZ + 2;

        List<?> expectedInterpolators = (List<?>)INTERPOLATORS.get(expected);
        List<?> actualInterpolators = (List<?>)INTERPOLATORS.get(actual);
        assertEquals(8, expectedInterpolators.size(), "ordinary Overworld interpolator closure");
        assertEquals(expectedInterpolators.size(), actualInterpolators.size());
        List<?> expectedCellCaches = (List<?>)CELL_CACHES.get(expected);
        List<?> actualCellCaches = (List<?>)CELL_CACHES.get(actual);
        assertEquals(expectedCellCaches.size(), actualCellCaches.size());
        assertTrue(!actualCellCaches.isEmpty());

        expected.initializeForFirstCellX();
        if (copiedCorners) {
            INTERPOLATING.setBoolean(actual, true);
            INTERPOLATION_COUNTER.setLong(actual, 0L);
            ARRAY_COUNTER.setLong(actual, sliceEpochs);
            copySlices(expectedInterpolators, actualInterpolators, "slice0");
            restoreSliceContext(actual, chunkMinX, chunkMinZ + 16, minY + settings.noiseSettings().height(), cellCountY);
            if (invalidateMemo) {
                assertTrue(invalidateMemoState(actual) > 0, "fixture must contain runtime CacheOnce wrappers");
            }
        } else {
            actual.initializeForFirstCellX();
        }

        try {
            for (int cellX = 0; cellX < cellCountXZ; cellX++) {
                expected.advanceCellX(cellX);
                if (copiedCorners) {
                    copySlices(expectedInterpolators, actualInterpolators, "slice1");
                    ARRAY_COUNTER.setLong(actual, ARRAY_COUNTER.getLong(actual) + sliceEpochs);
                    restoreSliceContext(actual, chunkMinX + cellX * cellWidth, chunkMinZ + 16,
                        minY + settings.noiseSettings().height(), cellCountY);
                    if (invalidateMemo) invalidateMemoState(actual);
                } else {
                    actual.advanceCellX(cellX);
                }

                for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                    for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                        expected.selectCellYZ(cellY, cellZ);
                        actual.selectCellYZ(cellY, cellZ);
                        for (int cache = 0; cache < expectedCellCaches.size(); cache++) {
                            double[] left = (double[])field(expectedCellCaches.get(cache).getClass(), "values").get(expectedCellCaches.get(cache));
                            double[] right = (double[])field(actualCellCaches.get(cache).getClass(), "values").get(actualCellCaches.get(cache));
                            assertEquals(left.length, right.length);
                            for (int entry = 0; entry < left.length; entry++) {
                                assertRawEquals(left[entry], right[entry], "cell-cache", chunkMinX, chunkMinZ, cellX, cellY, cellZ, entry);
                            }
                        }
                        for (int y = cellHeight - 1; y >= 0; y--) {
                            int blockY = minY + cellY * cellHeight + y;
                            expected.updateForY(blockY, (double)y / cellHeight);
                            actual.updateForY(blockY, (double)y / cellHeight);
                            for (int x = 0; x < cellWidth; x++) {
                                int blockX = chunkMinX + cellX * cellWidth + x;
                                expected.updateForX(blockX, (double)x / cellWidth);
                                actual.updateForX(blockX, (double)x / cellWidth);
                                for (int z = 0; z < cellWidth; z++) {
                                    int blockZ = chunkMinZ + cellZ * cellWidth + z;
                                    expected.updateForZ(blockZ, (double)z / cellWidth);
                                    actual.updateForZ(blockZ, (double)z / cellWidth);
                                    assertRawEquals((double)DENSITY.invoke(expected), (double)DENSITY.invoke(actual),
                                        "density", chunkMinX, chunkMinZ, blockX, blockY, blockZ, -1);
                                    BlockState leftState = (BlockState)BLOCK_STATE.invoke(expected);
                                    BlockState rightState = (BlockState)BLOCK_STATE.invoke(actual);
                                    assertEquals(leftState, rightState, () -> "block classification at " + blockX + "," + blockY + "," + blockZ);
                                    assertEquals(expected.aquifer().shouldScheduleFluidUpdate(), actual.aquifer().shouldScheduleFluidUpdate(),
                                        () -> "fluid-update flag at " + blockX + "," + blockY + "," + blockZ);
                                }
                            }
                        }
                    }
                }
                expected.swapSlices();
                actual.swapSlices();
            }
        } finally {
            expected.stopInterpolation();
            actual.stopInterpolation();
        }
    }

    private static NoiseChunk fixture(RegistryAccess registries, NoiseGeneratorSettings settings, int minX, int minZ) {
        RandomState randomState = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), 1196459378L);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        return new NoiseChunk(16 / settings.noiseSettings().getCellWidth(), randomState, minX, minZ,
            settings.noiseSettings(), EmptyBeardifier.INSTANCE, settings, picker, Blender.empty());
    }

    private static void copySlices(List<?> expected, List<?> actual, String sliceName) throws Exception {
        for (int interpolator = 0; interpolator < expected.size(); interpolator++) {
            Field source = field(expected.get(interpolator).getClass(), sliceName);
            double[][] left = (double[][])source.get(expected.get(interpolator));
            double[][] right = (double[][])source.get(actual.get(interpolator));
            assertEquals(left.length, right.length);
            for (int z = 0; z < left.length; z++) {
                assertEquals(left[z].length, right[z].length);
                System.arraycopy(left[z], 0, right[z], 0, left[z].length);
            }
        }
    }

    private static void restoreSliceContext(NoiseChunk chunk, int blockX, int blockZ, int blockY, int arrayIndex) throws Exception {
        field(NoiseChunk.class, "cellStartBlockX").setInt(chunk, blockX);
        field(NoiseChunk.class, "cellStartBlockY").setInt(chunk, blockY);
        field(NoiseChunk.class, "cellStartBlockZ").setInt(chunk, blockZ);
        field(NoiseChunk.class, "inCellX").setInt(chunk, 0);
        field(NoiseChunk.class, "inCellY").setInt(chunk, 0);
        field(NoiseChunk.class, "inCellZ").setInt(chunk, 0);
        field(NoiseChunk.class, "arrayIndex").setInt(chunk, arrayIndex);
    }

    private static int invalidateMemoState(NoiseChunk chunk) throws Exception {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int invalidated = 0;
        for (Object value : ((Map<?, ?>)WRAPPED.get(chunk)).values()) {
            if (!visited.add(value) || !value.getClass().getName().equals(NoiseChunk.class.getName() + "$CacheOnce")) continue;
            field(value.getClass(), "lastCounter").setLong(value, Long.MIN_VALUE);
            field(value.getClass(), "lastArrayCounter").setLong(value, Long.MIN_VALUE);
            field(value.getClass(), "lastArray").set(value, null);
            invalidated++;
        }
        return invalidated;
    }

    private static void assertRawEquals(double expected, double actual, String stage,
                                       int originX, int originZ, int x, int y, int z, int entry) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
            () -> stage + " origin=" + originX + "," + originZ + " point=" + x + "," + y + "," + z + " entry=" + entry);
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method method(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private enum EmptyBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
    }
}
