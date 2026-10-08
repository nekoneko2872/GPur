package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.compute.VanillaTerrainInterpolation;
import org.gpur.compute.VanillaTerrainSlab;
import org.junit.jupiter.api.Test;

/** Exercises NoiseChunk's actual interpolator hook without creating or dispatching a Vulkan device. */
@VanillaFeature
public class NoiseChunkVanillaSlabParityTest {
    private static final int CELL_WIDTH = 4;
    private static final int CELL_HEIGHT = 8;
    private static final int CELL_COUNT_Y = 16;
    private static final int X_SLABS_TO_CHECK = 2;

    private static final Field INTERPOLATORS = field(NoiseChunk.class, "interpolators");
    private static final Field SLICE0 = field(NoiseChunk.NoiseInterpolator.class, "slice0");
    private static final Field SLICE1 = field(NoiseChunk.NoiseInterpolator.class, "slice1");
    private static final Field INTERPOLATOR_INDEX = field(NoiseChunk.NoiseInterpolator.class, "gpurIndex");
    private static final Field ACTIVE_SLAB = field(NoiseChunk.class, "gpurSlab");
    private static final Constructor<VanillaTerrainSlab> SLAB_CONSTRUCTOR = slabConstructor();

    @Test
    void cpuReferenceSlabsPreserveNoiseChunkDensitiesBlocksAndAquiferFlags() throws Exception {
        WorldgenFixture fixture = worldgenFixture();
        compareChunk(-0x1234_5678_9abc_defL, -2, -3, fixture);
        // Keep both axes inside the world border while exercising large positive and negative coordinates.
        compareChunk(0xfedc_ba98_7654_3210L, 1_874_998, -1_874_999, fixture);
    }

    private static void compareChunk(long seed, int chunkX, int chunkZ, WorldgenFixture fixture) throws Exception {
        NoiseGeneratorSettings settings = smallOverworldSettings(fixture.overworldSettings());
        NoiseChunk cpu = createNoiseChunk(seed, chunkX, chunkZ, settings, fixture.noiseLookup());
        NoiseChunk slabBacked = createNoiseChunk(seed, chunkX, chunkZ, settings, fixture.noiseLookup());
        int chunkMinX = chunkX * 16;
        int chunkMinZ = chunkZ * 16;

        cpu.initializeForFirstCellX();
        slabBacked.initializeForFirstCellX();
        try {
            for (int cellXIndex = 0; cellXIndex < X_SLABS_TO_CHECK; cellXIndex++) {
                cpu.advanceCellX(cellXIndex);
                slabBacked.advanceCellX(cellXIndex);

                List<?> interpolators = interpolators(slabBacked);
                List<?> cpuInterpolators = interpolators(cpu);
                assertFalse(interpolators.isEmpty(), "The vanilla Overworld router should contain interpolated density nodes");
                assertEquals(interpolators.size(), cpuInterpolators.size());
                assertTrue(interpolators.size() <= VanillaTerrainInterpolation.MAX_INTERPOLATORS);
                double[][][] lower = new double[interpolators.size()][][];
                double[][][] upper = new double[interpolators.size()][][];
                for (int interpolator = 0; interpolator < interpolators.size(); interpolator++) {
                    Object node = interpolators.get(interpolator);
                    assertEquals(interpolator, INTERPOLATOR_INDEX.getInt(node), "GPU indices must match NoiseChunk list order");
                    lower[interpolator] = copySlice((double[][])SLICE0.get(node));
                    upper[interpolator] = copySlice((double[][])SLICE1.get(node));
                }
                int[] encoded = VanillaTerrainInterpolation.input(CELL_WIDTH, CELL_HEIGHT, CELL_COUNT_Y, lower, upper);
                int[] cpuSlabValues = VanillaTerrainInterpolation.reference(encoded);
                ACTIVE_SLAB.set(slabBacked, SLAB_CONSTRUCTOR.newInstance(cpuSlabValues, CELL_WIDTH, CELL_HEIGHT, CELL_COUNT_Y));

                // Match NoiseBasedChunkGenerator.doFill's Y/Z cell and block traversal order.
                for (int cellZIndex = 0; cellZIndex < 4; cellZIndex++) {
                    for (int cellYIndex = CELL_COUNT_Y - 1; cellYIndex >= 0; cellYIndex--) {
                        cpu.selectCellYZ(cellYIndex, cellZIndex);
                        slabBacked.selectCellYZ(cellYIndex, cellZIndex);
                        for (int yInCell = CELL_HEIGHT - 1; yInCell >= 0; yInCell--) {
                            int posY = -64 + cellYIndex * CELL_HEIGHT + yInCell;
                            cpu.updateForY(posY, (double)yInCell / CELL_HEIGHT);
                            slabBacked.updateForY(posY, (double)yInCell / CELL_HEIGHT);
                            for (int xInCell = 0; xInCell < CELL_WIDTH; xInCell++) {
                                int posX = chunkMinX + cellXIndex * CELL_WIDTH + xInCell;
                                cpu.updateForX(posX, (double)xInCell / CELL_WIDTH);
                                slabBacked.updateForX(posX, (double)xInCell / CELL_WIDTH);
                                for (int zInCell = 0; zInCell < CELL_WIDTH; zInCell++) {
                                    int posZ = chunkMinZ + cellZIndex * CELL_WIDTH + zInCell;
                                    cpu.updateForZ(posZ, (double)zInCell / CELL_WIDTH);
                                    slabBacked.updateForZ(posZ, (double)zInCell / CELL_WIDTH);
                                    String location = "chunk=" + chunkX + "," + chunkZ + " block=" + posX + "," + posY + "," + posZ;

                                    for (int interpolator = 0; interpolator < interpolators.size(); interpolator++) {
                                        double cpuInterpolated = ((DensityFunction)cpuInterpolators.get(interpolator)).compute(cpu);
                                        double slabInterpolated = ((DensityFunction)interpolators.get(interpolator)).compute(slabBacked);
                                        assertEquals(Double.doubleToRawLongBits(cpuInterpolated), Double.doubleToRawLongBits(slabInterpolated),
                                            "NoiseInterpolator value changed with CPU-reference slab at " + location);
                                    }

                                    double cpuDensity = cpu.getInterpolatedDensity();
                                    double slabDensity = slabBacked.getInterpolatedDensity();
                                    assertEquals(Double.doubleToRawLongBits(cpuDensity), Double.doubleToRawLongBits(slabDensity),
                                        "Density changed with CPU-reference slab at " + location);

                                    BlockState cpuState = cpu.getInterpolatedState();
                                    BlockState slabState = slabBacked.getInterpolatedState();
                                    assertEquals(cpuState, slabState, "Block state changed with CPU-reference slab at " + location);
                                    assertEquals(cpu.aquifer().shouldScheduleFluidUpdate(), slabBacked.aquifer().shouldScheduleFluidUpdate(),
                                        "Aquifer fluid-update decision changed at " + location);
                                }
                            }
                        }
                    }
                }

                cpu.swapSlices();
                slabBacked.swapSlices();
                assertNull(ACTIVE_SLAB.get(slabBacked), "An X-slab result must not leak across slice swaps");
            }
        } finally {
            ACTIVE_SLAB.set(slabBacked, null);
            cpu.stopInterpolation();
            slabBacked.stopInterpolation();
        }
        assertNull(ACTIVE_SLAB.get(slabBacked), "Stopping interpolation must clear the injected result");
    }

    private static NoiseChunk createNoiseChunk(
        long seed,
        int chunkX,
        int chunkZ,
        NoiseGeneratorSettings settings,
        HolderLookup<NormalNoise.NoiseParameters> noises
    ) {
        RandomState randomState = RandomState.create(settings, noises, seed);
        return new NoiseChunk(
            4,
            randomState,
            chunkX * 16,
            chunkZ * 16,
            settings.noiseSettings(),
            DensityFunctions.BeardifierMarker.INSTANCE,
            settings,
            fluidPicker(settings),
            Blender.empty()
        );
    }

    private static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidStatus empty = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> {
            if (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) return empty;
            return y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        };
    }

    private static NoiseGeneratorSettings smallOverworldSettings(NoiseGeneratorSettings original) {
        NoiseSettings size = NoiseSettings.create(-64, 128, original.noiseSettings().noiseSizeHorizontal(), original.noiseSettings().noiseSizeVertical());
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

    private static double[][] copySlice(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int z = 0; z < source.length; z++) copy[z] = source[z].clone();
        return copy;
    }

    private static List<?> interpolators(NoiseChunk chunk) throws IllegalAccessException {
        return (List<?>)INTERPOLATORS.get(chunk);
    }

    private static WorldgenFixture worldgenFixture() {
        try {
            RegistryAccess registries = RegistryHelper.registryAccess();
            NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            HolderLookup.RegistryLookup<NormalNoise.NoiseParameters> noises = registries.lookupOrThrow(Registries.NOISE);
            return new WorldgenFixture(settings, noises);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to create vanilla worldgen registries for NoiseChunk parity test", exception);
        }
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

    private record WorldgenFixture(
        NoiseGeneratorSettings overworldSettings,
        HolderLookup<NormalNoise.NoiseParameters> noiseLookup
    ) {}
}
