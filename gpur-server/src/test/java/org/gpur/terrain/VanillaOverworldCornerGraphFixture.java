package org.gpur.terrain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.support.RegistryHelper;

/**
 * Primitive graph inputs and independent untouched-CPU fillSlice outputs from the real default
 * Overworld. This is test-only; snapshots never install results into a live NoiseChunk.
 */
public final class VanillaOverworldCornerGraphFixture {
    private VanillaOverworldCornerGraphFixture() {}

    public record RootCase(int rootIndex, VanillaDensityGraph.Snapshot graph,
                           List<VanillaDensityGraphBatch.Sample> samples, int[] expectedWords) {
        public RootCase {
            samples = List.copyOf(samples);
            expectedWords = expectedWords.clone();
        }

        @Override public int[] expectedWords() { return this.expectedWords.clone(); }
    }

    /** The expected arrays originate only from NoiseChunk's unmodified CPU slice-fill loop. */
    public static List<RootCase> capture(final int minX, final int minZ) throws Exception {
        RegistryAccess registries = RegistryHelper.registryAccess();
        NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        NoiseChunk source = fixture(registries, settings, minX, minZ);
        NoiseChunk untouched = fixture(registries, settings, minX, minZ);
        VanillaDensityGraph.WrapperBindings bindings = source.gpurSnapshotCornerGraphBindings();
        if (bindings == null) throw new AssertionError("fresh empty-blender source was not admitted for snapshot capture");
        List<?> sourceInterpolators = (List<?>)field(NoiseChunk.class, "interpolators").get(source);
        List<?> cpuInterpolators = (List<?>)field(NoiseChunk.class, "interpolators").get(untouched);
        if (sourceInterpolators.size() != 8 || cpuInterpolators.size() != 8) {
            throw new AssertionError("default Overworld must contain all eight interpolator roots");
        }
        List<VanillaDensityGraph.Snapshot> graphs = new ArrayList<>();
        for (int root = 0; root < sourceInterpolators.size(); root++) {
            Object interpolator = sourceInterpolators.get(root);
            DensityFunction filler = (DensityFunction)field(interpolator.getClass(), "noiseFiller").get(interpolator);
            try {
                graphs.add(VanillaDensityGraph.snapshot(filler, VanillaDensityGraph.Limits.DEFAULT, bindings));
            } catch (VanillaDensityGraph.UnsupportedGraphException failure) {
                throw new AssertionError("default Overworld root " + root + " is not fully supported", failure);
            }
        }

        int width = settings.noiseSettings().getCellWidth();
        int height = settings.noiseSettings().getCellHeight();
        int cellsXZ = 16 / width;
        int cellsY = settings.noiseSettings().height() / height;
        int samplesPerRoot = (cellsXZ + 1) * (cellsXZ + 1) * (cellsY + 1);
        int[][] expected = new int[graphs.size()][samplesPerRoot * 2];
        List<VanillaDensityGraphBatch.Sample> positions = new ArrayList<>(samplesPerRoot);
        int sample = 0;
        untouched.initializeForFirstCellX();
        try {
            for (int cellX = 0; cellX <= cellsXZ; cellX++) {
                if (cellX > 0) untouched.advanceCellX(cellX - 1);
                String sliceName = cellX == 0 ? "slice0" : "slice1";
                for (int cellZ = 0; cellZ <= cellsXZ; cellZ++) {
                    for (int cellY = 0; cellY <= cellsY; cellY++) {
                        positions.add(new VanillaDensityGraphBatch.Sample(minX + cellX * width,
                            settings.noiseSettings().minY() + cellY * height, minZ + cellZ * width));
                        for (int root = 0; root < graphs.size(); root++) {
                            Object interpolator = cpuInterpolators.get(root);
                            double[][] slice = (double[][])field(interpolator.getClass(), sliceName).get(interpolator);
                            long bits = Double.doubleToRawLongBits(slice[cellZ][cellY]);
                            expected[root][sample * 2] = (int)bits;
                            expected[root][sample * 2 + 1] = (int)(bits >>> 32);
                        }
                        sample++;
                    }
                }
            }
        } finally {
            untouched.stopInterpolation();
        }
        if (sample != samplesPerRoot) throw new AssertionError("corner fixture sample sizing changed");
        List<RootCase> cases = new ArrayList<>(graphs.size());
        for (int root = 0; root < graphs.size(); root++) {
            cases.add(new RootCase(root, graphs.get(root), positions, expected[root]));
        }
        return List.copyOf(cases);
    }

    private static NoiseChunk fixture(final RegistryAccess registries, final NoiseGeneratorSettings settings,
                                      final int minX, final int minZ) {
        RandomState state = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), 1196459378L);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
        return new NoiseChunk(16 / settings.noiseSettings().getCellWidth(), state, minX, minZ,
            settings.noiseSettings(), EmptyBeardifier.INSTANCE, settings, picker, Blender.empty());
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

    private enum EmptyBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;
        @Override public double compute(final DensityFunction.FunctionContext context) { return 0.0; }
        @Override public double minValue() { return 0.0; }
        @Override public double maxValue() { return 0.0; }
    }
}
