package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
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
import org.junit.jupiter.api.Test;

/** Checks the numeric ranking against hand-calculated ties and vanilla's production aquifer loop. */
@VanillaFeature
class VanillaAquiferBatchTest {
    // The Overworld fixture below has min grid (-3,-7,-3), size (3,14,3).
    // The four cell coordinates are listed explicitly: >= insertion reverses visitation on ties.
    private static final int[] LITERAL_TOP_FOUR = {53, 50, 52, 49};
    private static final Field PRELIMINARY_SURFACE_LEVEL = field(NoiseChunk.class, "preliminarySurfaceLevel");
    private static final Field MIN_GRID_X = field(Aquifer.NoiseBasedAquifer.class, "minGridX");
    private static final Field MIN_GRID_Y = field(Aquifer.NoiseBasedAquifer.class, "minGridY");
    private static final Field MIN_GRID_Z = field(Aquifer.NoiseBasedAquifer.class, "minGridZ");
    private static final Field GRID_SIZE_X = field(Aquifer.NoiseBasedAquifer.class, "gridSizeX");
    private static final Field GRID_SIZE_Z = field(Aquifer.NoiseBasedAquifer.class, "gridSizeZ");
    private static final Field SKIP_SAMPLING_ABOVE_Y = field(Aquifer.NoiseBasedAquifer.class, "skipSamplingAboveY");
    private static final Field AQUIFER_LOCATION_CACHE = field(Aquifer.NoiseBasedAquifer.class, "aquiferLocationCache");
    private static final Field AQUIFER_CACHE = field(Aquifer.NoiseBasedAquifer.class, "aquiferCache");

    @Test
    void rankingMatchesLiteralTiesAcrossNegativeGridBoundariesAndVanillaLoop() throws Exception {
        // First fixture ties four centers at x/z=-18 and y=-20. The second places the
        // candidate anchors on the lower X/Z grid edge and crosses a negative Y-divide.
        Query[] queries = {
            new Query(-18, -18, -18, new int[]{53, 50, 52, 49}),
            new Query(-35, -26, -35, new int[]{49, 46, 40, 37})
        };
        for (Query query : queries) {
            AquiferFixture fixture = createAquiferFixture(query);
            assertEquals(-3, fixture.minGridX());
            assertEquals(-7, fixture.minGridY());
            assertEquals(-3, fixture.minGridZ());
            assertEquals(3, fixture.gridSizeX());
            assertEquals(14, fixture.gridSizeY());
            assertEquals(3, fixture.gridSizeZ());

            int[] snapshot = VanillaAquiferBatch.input(
                fixture.minGridX(), fixture.minGridY(), fixture.minGridZ(),
                fixture.gridSizeX(), fixture.gridSizeY(), fixture.gridSizeZ(),
                new int[]{query.x(), query.y(), query.z()}, fixture.centerCoordinates()
            );
            assertEquals(4, VanillaAquiferBatch.outputWords(snapshot));
            assertArrayEquals(query.literalTopFour(), VanillaAquiferBatch.reference(snapshot),
                "The literal equal-distance fixture must preserve vanilla's >= tie order");
            assertEquals(query.literalTopFour()[0], VanillaAquiferBatch.resultIndex(
                VanillaAquiferBatch.reference(snapshot), 0, 0
            ));

            // computeSubstance runs the actual NMS candidate loop. Equal fluid statuses make
            // it request all four ranked cells; the cache reveals the exact selected set.
            var result = fixture.aquifer().computeSubstance(
                new DensityFunction.SinglePointContext(query.x(), query.y(), query.z()), -1.0
            );
            assertEquals(
                SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                    ? Blocks.AIR.defaultBlockState()
                    : Blocks.WATER.defaultBlockState(),
                result
            );
            assertEquals(toSet(query.literalTopFour()), populatedCacheIndices(fixture.aquifer()),
                "The production aquifer must load exactly its four closest center cells");
        }
    }

    @Test
    void productionAquiferReturnsTheLiteralFirstRank() throws Exception {
        Query query = new Query(-18, -18, -18);
        AquiferFixture fixture = createAquiferFixture(query);
        Aquifer.FluidStatus[] cache = (Aquifer.FluidStatus[])AQUIFER_CACHE.get(fixture.aquifer());
        for (int index = 0; index < cache.length; index++) {
            cache[index] = new Aquifer.FluidStatus(1_000, Blocks.WATER.defaultBlockState());
        }
        // Only the hand-calculated first-ranked cell differs. computeSubstance's returned block
        // therefore independently checks the production loop's first index, not our CPU mirror.
        cache[LITERAL_TOP_FOUR[0]] = new Aquifer.FluidStatus(1_000, Blocks.DIRT.defaultBlockState());

        var result = fixture.aquifer().computeSubstance(
            new DensityFunction.SinglePointContext(query.x(), query.y(), query.z()), -1.0
        );
        assertEquals(
            SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                ? Blocks.AIR.defaultBlockState()
                : Blocks.DIRT.defaultBlockState(),
            result
        );
    }

    @Test
    void referenceMatchesVanillaForEveryNegativeWorldYAndDivisibilityBoundary() throws Exception {
        int minY = -64;
        int[] queries = new int[64 * 3];
        for (int offset = 0, y = minY; y < 0; y++, offset += 3) {
            queries[offset] = -25;
            queries[offset + 1] = y;
            queries[offset + 2] = -25;
        }

        AquiferFixture fixture = createAquiferFixture(new Query(-25, minY, -25));
        int[] snapshot = VanillaAquiferBatch.input(
            fixture.minGridX(), fixture.minGridY(), fixture.minGridZ(),
            fixture.gridSizeX(), fixture.gridSizeY(), fixture.gridSizeZ(),
            queries, fixture.centerCoordinates()
        );
        int[] ranks = VanillaAquiferBatch.reference(snapshot);
        assertArrayEquals(new int[]{13, 22, 14, 16}, Arrays.copyOfRange(ranks, 0, 4),
            "The Y=-64 fixture has a literal nearest-four order independent of cache loading");
        Aquifer.FluidStatus[] cache = (Aquifer.FluidStatus[])AQUIFER_CACHE.get(fixture.aquifer());

        for (int query = 0; query < 64; query++) {
            java.util.Arrays.fill(cache, null);
            int x = queries[query * 3];
            int y = queries[query * 3 + 1];
            int z = queries[query * 3 + 2];
            assertEquals(SharedConstants.DEBUG_DISABLE_FLUID_GENERATION
                    ? Blocks.AIR.defaultBlockState()
                    : Blocks.WATER.defaultBlockState(), fixture.aquifer().computeSubstance(
                new DensityFunction.SinglePointContext(x, y, z), -1.0
            ), "vanilla block state at Y=" + y);
            Set<Integer> expectedRanks = toSet(new int[]{
                ranks[query * 4], ranks[query * 4 + 1], ranks[query * 4 + 2], ranks[query * 4 + 3]
            });
            Set<Integer> loadedStatuses = populatedCacheIndices(fixture.aquifer());
            assertFalse(loadedStatuses.isEmpty(), "vanilla must load a nearest aquifer status at Y=" + y);
            assertTrue(expectedRanks.containsAll(loadedStatuses),
                "vanilla may load fewer than four nearest statuses after an early return at Y=" + y);
            assertTrue(loadedStatuses.contains(ranks[query * 4]),
                "vanilla must first load the exact nearest aquifer cell at Y=" + y);
        }
    }

    @Test
    void rejectsMalformedOrIncompleteNumericSnapshots() throws Exception {
        AquiferFixture fixture = createAquiferFixture(new Query(-18, -18, -18));
        int[] valid = VanillaAquiferBatch.input(
            fixture.minGridX(), fixture.minGridY(), fixture.minGridZ(),
            fixture.gridSizeX(), fixture.gridSizeY(), fixture.gridSizeZ(),
            new int[]{-18, -18, -18}, fixture.centerCoordinates()
        );

        int[] wrongQueryCount = valid.clone();
        wrongQueryCount[1] = 2;
        assertThrows(IllegalArgumentException.class, () -> VanillaAquiferBatch.validate(wrongQueryCount));

        int[] illegalCenter = valid.clone();
        int centerOffset = VanillaAquiferBatch.HEADER_WORDS + 3;
        illegalCenter[centerOffset] += 32;
        assertThrows(IllegalArgumentException.class, () -> VanillaAquiferBatch.validate(illegalCenter));

        assertThrows(IllegalArgumentException.class, () -> VanillaAquiferBatch.input(
            fixture.minGridX(), fixture.minGridY(), fixture.minGridZ(),
            fixture.gridSizeX(), fixture.gridSizeY(), fixture.gridSizeZ(),
            new int[]{-18, -18}, fixture.centerCoordinates()
        ));
    }

    @Test
    void rejectsExtremeGridDimensionsBeforeTheirProductCanOverflow() {
        assertThrows(IllegalArgumentException.class, () -> VanillaAquiferBatch.input(
            -2, -7, -2, 1 << 30, 1 << 30, 16,
            new int[]{0, 0, 0}, new int[0]
        ));
    }

    private static AquiferFixture createAquiferFixture(Query query) throws Exception {
        WorldgenFixture worldgen = worldgenFixture();
        NoiseGeneratorSettings settings = smallOverworldSettings(worldgen.overworldSettings());
        RandomState randomState = RandomState.create(settings, worldgen.noiseLookup(), 0x51a7_6eL);
        Aquifer.FluidPicker picker = (x, y, z) -> new Aquifer.FluidStatus(1_000, Blocks.WATER.defaultBlockState());
        NoiseChunk noiseChunk = new NoiseChunk(
            1,
            randomState,
            -32,
            -32,
            settings.noiseSettings(),
            TestBeardifier.INSTANCE,
            settings,
            picker,
            Blender.empty()
        );
        assertTrue(settings.isAquifersEnabled(), "The vanilla Overworld fixture must have aquifers enabled");
        PRELIMINARY_SURFACE_LEVEL.set(noiseChunk, DensityFunctions.constant(-10_000.0));
        assertInstanceOf(Aquifer.NoiseBasedAquifer.class, noiseChunk.aquifer());
        Aquifer.NoiseBasedAquifer aquifer = (Aquifer.NoiseBasedAquifer)noiseChunk.aquifer();
        SKIP_SAMPLING_ABOVE_Y.setInt(aquifer, Integer.MAX_VALUE);

        int minGridX = MIN_GRID_X.getInt(aquifer);
        int minGridY = MIN_GRID_Y.getInt(aquifer);
        int minGridZ = MIN_GRID_Z.getInt(aquifer);
        int gridSizeX = GRID_SIZE_X.getInt(aquifer);
        int gridSizeZ = GRID_SIZE_Z.getInt(aquifer);
        long[] locationCache = (long[])AQUIFER_LOCATION_CACHE.get(aquifer);
        int gridSizeY = locationCache.length / (gridSizeX * gridSizeZ);
        int[] centers = new int[locationCache.length * 3];
        int anchorX = (query.x() - 5) >> 4;
        int anchorY = Math.floorDiv(query.y() + 1, 12);
        int anchorZ = (query.z() - 5) >> 4;

        for (int yIndex = 0; yIndex < gridSizeY; yIndex++) {
            int gridY = minGridY + yIndex;
            for (int zIndex = 0; zIndex < gridSizeZ; zIndex++) {
                int gridZ = minGridZ + zIndex;
                for (int xIndex = 0; xIndex < gridSizeX; xIndex++) {
                    int gridX = minGridX + xIndex;
                    int cellIndex = (yIndex * gridSizeZ + zIndex) * gridSizeX + xIndex;
                    int centerX = centerCoordinate(gridX, anchorX, 16, 8);
                    int centerY = centerCoordinate(gridY, anchorY, 12, 4);
                    int centerZ = centerCoordinate(gridZ, anchorZ, 16, 8);

                    // The two fixtures use literal legal offsets to make the nearest ranks exact:
                    // (-18) uses -24/-12, while (-35) uses -39/-31. At y=-26, -32/-20 tie.
                    if (query.x() == -35 && (gridX == anchorX || gridX == anchorX + 1)) {
                        centerX = gridX == anchorX ? -39 : -31;
                    }
                    if (query.y() == -26 && (gridY == anchorY || gridY == anchorY + 1)) {
                        centerY = gridY == anchorY ? -32 : -20;
                    }
                    if (query.z() == -35 && (gridZ == anchorZ || gridZ == anchorZ + 1)) {
                        centerZ = gridZ == anchorZ ? -39 : -31;
                    }
                    centers[cellIndex * 3] = centerX;
                    centers[cellIndex * 3 + 1] = centerY;
                    centers[cellIndex * 3 + 2] = centerZ;
                    locationCache[cellIndex] = BlockPos.asLong(centerX, centerY, centerZ);
                }
            }
        }

        return new AquiferFixture(
            aquifer, minGridX, minGridY, minGridZ, gridSizeX, gridSizeY, gridSizeZ, centers
        );
    }

    private static int centerCoordinate(int grid, int anchor, int spacing, int tiedOffset) {
        return grid * spacing + (grid == anchor ? tiedOffset : 4);
    }

    private static Set<Integer> toSet(int[] values) {
        Set<Integer> set = new HashSet<>();
        for (int value : values) set.add(value);
        return set;
    }

    private static Set<Integer> populatedCacheIndices(Aquifer.NoiseBasedAquifer aquifer) throws IllegalAccessException {
        Aquifer.FluidStatus[] cache = (Aquifer.FluidStatus[])AQUIFER_CACHE.get(aquifer);
        Set<Integer> populated = new HashSet<>();
        for (int index = 0; index < cache.length; index++) {
            if (cache[index] != null) populated.add(index);
        }
        return populated;
    }

    private static NoiseGeneratorSettings smallOverworldSettings(NoiseGeneratorSettings original) {
        NoiseSettings size = NoiseSettings.create(
            -64,
            128,
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

    private record Query(int x, int y, int z, int[] literalTopFour) {
        private Query(int x, int y, int z) {
            this(x, y, z, LITERAL_TOP_FOUR);
        }
    }

    private record AquiferFixture(
        Aquifer.NoiseBasedAquifer aquifer,
        int minGridX,
        int minGridY,
        int minGridZ,
        int gridSizeX,
        int gridSizeY,
        int gridSizeZ,
        int[] centerCoordinates
    ) {}

    private record WorldgenFixture(
        NoiseGeneratorSettings overworldSettings,
        HolderLookup<NormalNoise.NoiseParameters> noiseLookup
    ) {}

    private enum TestBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return 0.0;
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 0.0;
        }
    }
}
