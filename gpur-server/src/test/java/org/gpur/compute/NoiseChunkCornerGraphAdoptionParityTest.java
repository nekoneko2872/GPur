package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
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
import org.gpur.GPurConfig;
import org.gpur.terrain.VanillaCornerGraphPreparation;
import org.gpur.terrain.VanillaDensityGraphBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the live fillSlice consumer against a separate untouched vanilla owner. */
@VanillaFeature
public class NoiseChunkCornerGraphAdoptionParityTest {
    private final Map<Field, Object> previous = new LinkedHashMap<>();

    @BeforeEach
    void configure() throws Exception {
        set("gpuAccelerationEnabled", true);
        set("terrainGpuEnabled", true);
        set("gpuAsyncSubmit", true);
        set("gpuBufferBudgetMiB", 64);
        set("vanillaTerrainEnabled", true);
        set("vanillaTerrainMode", VanillaVerificationPolicy.Mode.STRICT);
        set("vanillaTerrainMinValues", 1_024);
        set("vanillaTerrainMaxSlabValues", 65_536);
        set("vanillaNoiseGpuEnabled", false);
        set("vanillaAquiferGpuEnabled", false);
        set("vanillaCornerGraphsGpuEnabled", true);
    }

    @AfterEach
    void restore() throws Exception {
        for (var entry : this.previous.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test
    void graphReferenceResultsMatchEveryOverworldBlockAndCacheAtBothCoordinateSigns() throws Exception {
        try (ComputeService service = simulatedService()) {
            compareCompleteChunk(service, 0, 0, false);
            compareCompleteChunk(service, -32, -48, false);
            assertEquals(19_600, consumed(service));
        }
    }

    @Test
    void retiringAfterOneSlabReturnsToOriginalCpuFillsWithoutChangingBlocks() throws Exception {
        try (ComputeService service = simulatedService()) {
            compareCompleteChunk(service, -16, 32, true);
            assertEquals(3_920, consumed(service), "only the initial and next X slice were consumed before retirement");
        }
    }

    /** Also called by the physical-GPU test; callers must construct a strict W7-enabled service. */
    public static void compareCompleteChunk(ComputeService service, int minX, int minZ, boolean retireAfterFirstSlab) throws Exception {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        NoiseChunk expected = fixture(registries, settings, minX, minZ);
        NoiseChunk actual = fixture(registries, settings, minX, minZ);
        assertTrue(service.worldgenEligible(VanillaDensityGraphBatch.WORKLOAD));
        long beforeConsumption = consumed(service);
        VanillaCornerGraphPreparation.Prepared graphs = actual.gpurPrepareCornerGraphsAsync(service, Runnable::run).join();
        assertNotNull(graphs, "all eight original roots must be supported and prepared completely");
        assertTrue(graphs.usable());
        assertEquals(beforeConsumption, consumed(service), "preparing a frame is not adopting it");
        actual.gpurInstallCornerGraphs(graphs);
        assertSame(graphs, field(NoiseChunk.class, "gpurCornerGraphs").get(actual));

        int width = settings.noiseSettings().getCellWidth();
        int height = settings.noiseSettings().getCellHeight();
        int cellsXZ = 16 / width;
        int cellsY = settings.noiseSettings().height() / height;
        int minY = settings.noiseSettings().minY();
        Method density = method(NoiseChunk.class, "getInterpolatedDensity");
        Method blockState = method(NoiseChunk.class, "getInterpolatedState");
        List<?> expectedInterpolators = (List<?>)field(NoiseChunk.class, "interpolators").get(expected);
        List<?> actualInterpolators = (List<?>)field(NoiseChunk.class, "interpolators").get(actual);
        assertEquals(8, expectedInterpolators.size());
        assertEquals(8, actualInterpolators.size());
        List<?> expectedCaches = (List<?>)field(NoiseChunk.class, "cellCaches").get(expected);
        List<?> actualCaches = (List<?>)field(NoiseChunk.class, "cellCaches").get(actual);
        assertEquals(expectedCaches.size(), actualCaches.size());
        expected.initializeForFirstCellX();
        actual.initializeForFirstCellX();
        try {
            compareSlices(expectedInterpolators, actualInterpolators, "slice0");
            for (int cellX = 0; cellX < cellsXZ; cellX++) {
                expected.advanceCellX(cellX);
                actual.advanceCellX(cellX);
                compareSlices(expectedInterpolators, actualInterpolators, "slice1");
                assertEquals(field(NoiseChunk.class, "arrayInterpolationCounter").getLong(expected),
                    field(NoiseChunk.class, "arrayInterpolationCounter").getLong(actual), "original outer array epochs remain intact");
                for (int cellZ = 0; cellZ < cellsXZ; cellZ++) {
                    for (int cellY = cellsY - 1; cellY >= 0; cellY--) {
                        expected.selectCellYZ(cellY, cellZ);
                        actual.selectCellYZ(cellY, cellZ);
                        for (int cache = 0; cache < expectedCaches.size(); cache++) {
                            double[] left = (double[])field(expectedCaches.get(cache).getClass(), "values").get(expectedCaches.get(cache));
                            double[] right = (double[])field(actualCaches.get(cache).getClass(), "values").get(actualCaches.get(cache));
                            assertEquals(left.length, right.length);
                            for (int index = 0; index < left.length; index++) assertRawEquals(left[index], right[index]);
                        }
                        for (int y = height - 1; y >= 0; y--) {
                            int blockY = minY + cellY * height + y;
                            expected.updateForY(blockY, (double)y / height);
                            actual.updateForY(blockY, (double)y / height);
                            for (int x = 0; x < width; x++) {
                                int blockX = minX + cellX * width + x;
                                expected.updateForX(blockX, (double)x / width);
                                actual.updateForX(blockX, (double)x / width);
                                for (int z = 0; z < width; z++) {
                                    int blockZ = minZ + cellZ * width + z;
                                    expected.updateForZ(blockZ, (double)z / width);
                                    actual.updateForZ(blockZ, (double)z / width);
                                    assertRawEquals((double)density.invoke(expected), (double)density.invoke(actual));
                                    BlockState expectedState = (BlockState)blockState.invoke(expected);
                                    BlockState actualState = (BlockState)blockState.invoke(actual);
                                    assertEquals(expectedState, actualState, () -> "block classification at " + blockX + "," + blockY + "," + blockZ);
                                    assertEquals(expected.aquifer().shouldScheduleFluidUpdate(), actual.aquifer().shouldScheduleFluidUpdate(),
                                        () -> "fluid-update flag at " + blockX + "," + blockY + "," + blockZ);
                                }
                            }
                        }
                    }
                }
                expected.swapSlices();
                actual.swapSlices();
                if (cellX == 0 && retireAfterFirstSlab) service.close();
            }
        } finally {
            expected.stopInterpolation();
            actual.stopInterpolation();
        }
        assertFalse(graphs.usable(), "stopInterpolation releases all numeric ownership");
        assertNull(field(NoiseChunk.class, "gpurCornerGraphs").get(actual));
        assertEquals(retireAfterFirstSlab ? 3_920 : 9_800, consumed(service) - beforeConsumption);
    }

    private static void compareSlices(List<?> expected, List<?> actual, String name) throws Exception {
        for (int root = 0; root < expected.size(); root++) {
            double[][] left = (double[][])field(expected.get(root).getClass(), name).get(expected.get(root));
            double[][] right = (double[][])field(actual.get(root).getClass(), name).get(actual.get(root));
            assertEquals(left.length, right.length);
            for (int z = 0; z < left.length; z++) {
                assertEquals(left[z].length, right[z].length);
                for (int y = 0; y < left[z].length; y++) assertRawEquals(left[z][y], right[z][y]);
            }
        }
    }

    private static NoiseChunk fixture(RegistryAccess registries, NoiseGeneratorSettings settings, int minX, int minZ) {
        RandomState random = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), 1196459378L);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        return new NoiseChunk(16 / settings.noiseSettings().getCellWidth(), random, minX, minZ, settings.noiseSettings(),
            EmptyBeardifier.INSTANCE, settings, (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea, Blender.empty());
    }

    private static long consumed(ComputeService service) {
        return service.worldgenDevices().stream().filter(status -> status.workload() == VanillaDensityGraphBatch.WORKLOAD)
            .mapToLong(ComputeService.WorldgenDeviceStatus::consumedValues).sum();
    }

    private static ComputeService simulatedService() {
        VulkanDevice device = mock(VulkanDevice.class);
        when(device.available()).thenReturn(true);
        when(device.name()).thenReturn("corner-graph-reference-simulated");
        when(device.uuid()).thenReturn("corner-graph-reference-simulated-uuid");
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(call -> {
            int[] input = call.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        return new ComputeService(Logger.getLogger("corner-graph-adoption"), List.of(device));
    }

    private void set(String name, Object value) throws Exception {
        Field field = GPurConfig.class.getField(name);
        this.previous.put(field, field.get(null));
        field.set(null, value);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static void assertRawEquals(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private enum EmptyBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
    }
}
