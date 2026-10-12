package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.data.worldgen.TerrainProvider;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VanillaDensityGraphBatchTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void versionedWireReferenceMatchesSnapshotForOperatorsAndInitializedProfiles() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x71b2L), -2, 0.75, 1.0, 0.375, 0.5);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction y = DensityFunctions.yClampedGradient(-64, 320, -0.3, 0.7);
        DensityFunction noise = DensityFunctions.noise(holder, 0.125, 0.0625);
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            DensityFunctions.shift(holder), DensityFunctions.shiftA(holder), 0.03125, holder
        );
        DensityFunction mapped = DensityFunctions.constant(-0.75).abs().square().cube()
            .halfNegative().quarterNegative().squeeze();
        DensityFunction inverted = DensityFunctions.constant(-0.75).invert();
        DensityFunction selected = DensityFunctions.intervalSelect(
            DensityFunctions.add(y, DensityFunctions.constant(0.125)),
            new DoubleArrayList(new double[] {-0.15, 0.0, 0.25}),
            List.of(
                DensityFunctions.add(DensityFunctions.add(noise, DensityFunctions.add(mapped, inverted)),
                    DensityFunctions.constant(-0.75).square()),
                DensityFunctions.rangeChoice(y, -0.1, 0.2, shifted, noise),
                DensityFunctions.min(DensityFunctions.mul(noise, y), DensityFunctions.max(shifted, y)),
                DensityFunctions.add(noise, y).clamp(-1.0, 1.0)
            )
        );
        DensityFunction root = DensityFunctions.add(initialize(selected, normal),
            new BlendedNoise(RandomSource.create(0x991L), 1.0, 1.0, 8.55515, 4.277575, 2.0));
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        List<VanillaDensityGraphBatch.Sample> samples = List.of(
            new VanillaDensityGraphBatch.Sample(-30_000_000, -65, 30_000_000),
            new VanillaDensityGraphBatch.Sample(-31, -64, 47),
            new VanillaDensityGraphBatch.Sample(-1, -1, -1),
            new VanillaDensityGraphBatch.Sample(0, 0, 0),
            new VanillaDensityGraphBatch.Sample(73, 64, -911),
            new VanillaDensityGraphBatch.Sample(17_000_000, 320, -19_000_000),
            new VanillaDensityGraphBatch.Sample(13, 321, -5)
        );

        int[] input = VanillaDensityGraphBatch.input(snapshot, samples);
        assertEquals(VanillaDensityGraphBatch.WORKLOAD, input[0]);
        assertEquals(samples.size(), input[1]);
        assertEquals(VanillaDensityGraphBatch.WIRE_VERSION, input[3]);
        assertEquals(24, input[11]);
        assertEquals(input[4] - 1, input[5]);
        assertEquals(input.length, input[2]);
        assertEquals(samples.size() * 2, VanillaDensityGraphBatch.outputWords(input));

        int[] output = VanillaDensityGraphBatch.reference(input);
        for (int i = 0; i < samples.size(); i++) {
            VanillaDensityGraphBatch.Sample sample = samples.get(i);
            double expected = snapshot.evaluate(sample.x(), sample.y(), sample.z());
            double actual = VanillaDensityGraphBatch.outputValue(output, i);
            assertRawEquals(expected, actual, "sample " + i);
            double vanilla = root.compute(new DensityFunction.SinglePointContext(sample.x(), sample.y(), sample.z()));
            assertRawEquals(vanilla, actual, "actual vanilla root sample " + i);
        }
    }

    @Test
    void splineWireReferenceMatchesMacheFloatBitsForNoiseCoordinatesAndLazyBranches() {
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
        DensityFunction root = initialize(new DensityFunctions.Spline(outer), normal);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        int[][] positions = {
            {-911, -64, 47}, {-1, -32, -1}, {73, -24, -911}, {17, -16, -19},
            {-16, 0, 16}, {243, 16, -77}, {0, 32, 0}, {-13, 48, 7}, {9, 64, -27}
        };
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(positions.length);
        for (int[] position : positions) {
            samples.add(new VanillaDensityGraphBatch.Sample(position[0], position[1], position[2]));
        }

        int[] input = VanillaDensityGraphBatch.input(snapshot, samples);
        int splineConstants = 0;
        int splineMultipoints = 0;
        for (int node = 0; node < input[4]; node++) {
            int record = input[11] + node * VanillaDensityGraphBatch.NODE_WORDS;
            if (input[record] == 13) splineConstants++;
            if (input[record] == 14) splineMultipoints++;
        }
        assertTrue(splineConstants > 0, "spline constants must use the v2 binary32 payload");
        assertTrue(splineMultipoints >= 2, "nested and outer multipoints share the same postorder DAG");

        int[] output = VanillaDensityGraphBatch.reference(input);
        for (int sample = 0; sample < samples.size(); sample++) {
            VanillaDensityGraphBatch.Sample point = samples.get(sample);
            double actual = VanillaDensityGraphBatch.outputValue(output, sample);
            assertRawEquals(snapshot.evaluate(point.x(), point.y(), point.z()), actual,
                "snapshot spline sample " + sample);
            assertRawEquals(root.compute(new DensityFunction.SinglePointContext(point.x(), point.y(), point.z())), actual,
                "Mache spline sample " + sample);
        }

        int multipointRecord = -1;
        for (int node = 0; node < input[4]; node++) {
            int record = input[11] + node * VanillaDensityGraphBatch.NODE_WORDS;
            if (input[record] == 14) {
                multipointRecord = record;
                break;
            }
        }
        assertTrue(multipointRecord >= 0);
        int firstPointAux = input[13] + input[multipointRecord + 4];

        int[] duplicateKnot = input.clone();
        duplicateKnot[firstPointAux + 2] = duplicateKnot[firstPointAux];
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(duplicateKnot));

        int[] infiniteDerivative = input.clone();
        infiniteDerivative[firstPointAux + 1] = 0x7f800000;
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(infiniteDerivative));

        int[] forgedBounds = input.clone();
        int node = (multipointRecord - input[11]) / VanillaDensityGraphBatch.NODE_WORDS;
        putDouble(forgedBounds, input[13] + node * 4, 1_000.0);
        putDouble(forgedBounds, input[13] + node * 4 + 2, 1_001.0);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(forgedBounds));
    }

    @Test
    void adversarialSplineFloatCasesMatchActualMacheAndWireRawBits() {
        DensityFunction root = adversarialSplineGraph();
        int branchCount = 10;
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(branchCount);
        for (int branch = 0; branch < branchCount; branch++) {
            samples.add(new VanillaDensityGraphBatch.Sample(branch - 7, branch, 11 - branch));
        }
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        int[] input = VanillaDensityGraphBatch.input(snapshot, samples);
        int splineConstants = 0;
        int splineMultipoints = 0;
        boolean positiveSubnormalConstant = false;
        boolean negativeSubnormalConstant = false;
        boolean positiveSubnormalPointData = false;
        boolean negativeSubnormalPointData = false;
        for (int node = 0; node < input[4]; node++) {
            int record = input[11] + node * VanillaDensityGraphBatch.NODE_WORDS;
            if (input[record] == 13) {
                splineConstants++;
                positiveSubnormalConstant |= input[record + 6] == 0x00000001;
                negativeSubnormalConstant |= input[record + 6] == 0x80000001;
            } else if (input[record] == 14) {
                splineMultipoints++;
                int pointCount = input[record + 6];
                int aux = input[13] + input[record + 4];
                for (int point = 0; point < pointCount; point++) {
                    for (int field = 0; field < 2; field++) {
                        int bits = input[aux + point * 2 + field];
                        positiveSubnormalPointData |= bits == 0x00000001;
                        negativeSubnormalPointData |= bits == 0x80000001;
                    }
                }
            }
        }
        assertTrue(splineConstants >= 2, "positive and negative subnormal constants must use the binary32 wire payload");
        assertTrue(splineMultipoints >= 7, "all adversarial multipoints must remain represented in the unified graph");
        assertTrue(positiveSubnormalConstant);
        assertTrue(negativeSubnormalConstant);
        assertTrue(positiveSubnormalPointData, "positive subnormal knot or derivative must be encoded");
        assertTrue(negativeSubnormalPointData, "negative subnormal knot or derivative must be encoded");

        int[] output = VanillaDensityGraphBatch.reference(input);
        for (int sample = 0; sample < samples.size(); sample++) {
            VanillaDensityGraphBatch.Sample point = samples.get(sample);
            double mache = root.compute(new DensityFunction.SinglePointContext(point.x(), point.y(), point.z()));
            assertTrue(Double.isFinite(mache), "overflow-safe spline sample " + sample);
            assertRawEquals(mache, snapshot.evaluate(point.x(), point.y(), point.z()), "snapshot adversarial sample " + sample);
            assertRawEquals(mache, VanillaDensityGraphBatch.outputValue(output, sample), "wire adversarial sample " + sample);
        }
        assertRawEquals((double)Float.MIN_VALUE, VanillaDensityGraphBatch.outputValue(output, 0), "positive subnormal constant");
        assertRawEquals((double)-Float.MIN_VALUE, VanillaDensityGraphBatch.outputValue(output, 1), "negative subnormal constant");
        assertRawEquals((double)-Float.MIN_VALUE, VanillaDensityGraphBatch.outputValue(output, 3), "negative subnormal extension");
        assertRawEquals((double)(Float.MIN_NORMAL / 2.0F), VanillaDensityGraphBatch.outputValue(output, 4), "normal/subnormal midpoint");
        assertRawEquals((double)Float.MIN_NORMAL, VanillaDensityGraphBatch.outputValue(output, 5), "least normal endpoint");
        assertRawEquals(0.0, VanillaDensityGraphBatch.outputValue(output, 6), "zero spline fraction");
        assertRawEquals((double)(1.0F / 3.0F), VanillaDensityGraphBatch.outputValue(output, 7), "binary32 one-third division");
        assertRawEquals(0.0, VanillaDensityGraphBatch.outputValue(output, 8), "midpoint-to-subnormal tie rounds to even zero");
        float wide = Float.MAX_VALUE / 4.0F;
        assertRawEquals((double)(wide * 1.5F), VanillaDensityGraphBatch.outputValue(output, 9), "wide finite interpolation");
    }

    private static DensityFunction adversarialSplineGraph() {
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
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>builder(transitionCoordinate)
            .addPoint(0.0F, 0.0F).addPoint(1.0F, Float.MIN_NORMAL).build()));

        DensityFunctions.Spline.Coordinate upperTransitionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(1.0)
        );
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>builder(upperTransitionCoordinate)
            .addPoint(0.0F, 0.0F).addPoint(1.0F, Float.MIN_NORMAL).build()));

        DensityFunctions.Spline.Coordinate zeroDivisionCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.0)
        );
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>builder(zeroDivisionCoordinate)
            .addPoint(0.0F, 0.0F).addPoint(3.0F, 1.0F).build()));

        DensityFunctions.Spline.Coordinate oneThirdCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(1.0)
        );
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>builder(oneThirdCoordinate)
            .addPoint(0.0F, 0.0F, 1.0F / 3.0F).addPoint(3.0F, 1.0F, 1.0F / 3.0F).build()));

        DensityFunctions.Spline.Coordinate halfwayCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.5)
        );
        branches.add(new DensityFunctions.Spline(CubicSpline.<DensityFunctions.Spline.Coordinate>builder(halfwayCoordinate)
            .addPoint(0.0F, 0.0F).addPoint(1.0F, Float.MIN_VALUE).build()));

        float wide = Float.MAX_VALUE / 4.0F;
        DensityFunctions.Spline.Coordinate wideCoordinate = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.0)
        );
        branches.add(new DensityFunctions.Spline(new CubicSpline.Multipoint<>(
            wideCoordinate,
            new float[] {-wide, wide},
            List.of(
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(wide),
                CubicSpline.<DensityFunctions.Spline.Coordinate>constant(wide * 2.0F)
            ),
            new float[] {0.0F, 0.0F}
        )));

        int branchCount = branches.size();
        double[] thresholds = new double[branchCount - 1];
        for (int branch = 0; branch < thresholds.length; branch++) thresholds[branch] = branch + 0.5;
        DensityFunction selector = DensityFunctions.yClampedGradient(0, branchCount - 1, 0.0, branchCount - 1.0);
        return DensityFunctions.intervalSelect(selector, new DoubleArrayList(thresholds), branches);
    }

    @Test
    void terrainProviderSplineWireReferenceMatchesMacheForSyntheticCoordinateGraphs() {
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
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        int[] ys = {-140, -128, -100, -80, -50, -30, -15, 0, 25, 50, 75, 100, 130};
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(ys.length);
        for (int i = 0; i < ys.length; i++) {
            samples.add(new VanillaDensityGraphBatch.Sample(-113 + i * 17, ys[i], 257 - i * 31));
        }

        int[] input = VanillaDensityGraphBatch.input(snapshot, samples);
        int[] output = VanillaDensityGraphBatch.reference(input);
        for (int sample = 0; sample < samples.size(); sample++) {
            VanillaDensityGraphBatch.Sample point = samples.get(sample);
            double actual = VanillaDensityGraphBatch.outputValue(output, sample);
            assertRawEquals(snapshot.evaluate(point.x(), point.y(), point.z()), actual,
                "TerrainProvider snapshot sample " + sample);
            assertRawEquals(root.compute(new DensityFunction.SinglePointContext(point.x(), point.y(), point.z())), actual,
                "TerrainProvider Mache sample " + sample);
        }
    }

    @Test
    void exporterRetainsSharedIdentityAndRejectsMalformedSectionsEdgesAndProfiles() {
        DensityFunction shared = DensityFunctions.yClampedGradient(-32, 48, -2.0, 3.0);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(DensityFunctions.add(shared, shared));
        int[] valid = VanillaDensityGraphBatch.input(snapshot, new VanillaDensityGraphBatch.Sample(-17, 19, 23));
        assertEquals(2, valid[4], "the shared vanilla child is emitted once");
        VanillaDensityGraphBatch.validate(valid);
        double sharedExpected = DensityFunctions.add(shared, shared).compute(
            new DensityFunction.SinglePointContext(-17, 19, 23)
        );
        assertRawEquals(sharedExpected, VanillaDensityGraphBatch.outputValue(VanillaDensityGraphBatch.reference(valid), 0),
            "shared child can be evaluated by both parents without being mistaken for a cycle");

        int[] invalidHeader = valid.clone();
        invalidHeader[1]++;
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(invalidHeader));

        int[] invalidSection = valid.clone();
        invalidSection[12]++;
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(invalidSection));

        int[] selfCycle = valid.clone();
        int rootRecord = selfCycle[11] + selfCycle[5] * VanillaDensityGraphBatch.NODE_WORDS;
        selfCycle[selfCycle[12] + selfCycle[rootRecord + 2]] = selfCycle[5];
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(selfCycle));

        DensityFunction interval = DensityFunctions.intervalSelect(
            DensityFunctions.constant(0.0), new DoubleArrayList(new double[] {0.0, 1.0}),
            List.of(DensityFunctions.constant(1.0), DensityFunctions.constant(2.0), DensityFunctions.constant(3.0))
        );
        int[] unsortedThresholds = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(interval),
            new VanillaDensityGraphBatch.Sample(0, 0, 0));
        int intervalRecord = unsortedThresholds[11] + unsortedThresholds[5] * VanillaDensityGraphBatch.NODE_WORDS;
        int thresholdOffset = unsortedThresholds[13] + unsortedThresholds[intervalRecord + 4];
        putDouble(unsortedThresholds, thresholdOffset, 2.0);
        putDouble(unsortedThresholds, thresholdOffset + 2, 1.0);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(unsortedThresholds));

        NormalNoise normal = NormalNoise.create(RandomSource.create(0x102L), -1, 1.0, 0.5, 0.25);
        DensityFunction noise = initialize(DensityFunctions.noise(Holder.direct(normal.parameters()), 0.25, 0.5), normal);
        int[] withProfile = VanillaDensityGraphBatch.input(VanillaDensityGraph.snapshot(noise),
            new VanillaDensityGraphBatch.Sample(1, 2, 3));
        int[] badProfileRoot = withProfile.clone();
        badProfileRoot[badProfileRoot[14]] = badProfileRoot[15] + badProfileRoot[10];
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(badProfileRoot));
    }

    @Test
    void validatorRejectsDepthAndWeightedBatchWorkBeforeReferenceEvaluation() {
        DensityFunction deep = DensityFunctions.constant(0.125);
        for (int i = 0; i < 130; i++) deep = deep.abs();
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(deep);
        List<VanillaDensityGraphBatch.Sample> large = new ArrayList<>(VanillaDensityGraphBatch.MAX_SAMPLES);
        for (int i = 0; i < VanillaDensityGraphBatch.MAX_SAMPLES; i++) {
            large.add(new VanillaDensityGraphBatch.Sample(0, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.input(snapshot, large));

        DensityFunction deeper = DensityFunctions.constant(0.0);
        for (int i = 0; i < VanillaDensityGraphBatch.MAX_DEPTH; i++) deeper = deeper.abs();
        VanillaDensityGraph.Snapshot overDepth = VanillaDensityGraph.snapshot(
            deeper,
            new VanillaDensityGraph.Limits(8_192, 32_768, 512, 2_048, 1_024)
        );
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.input(
            overDepth, new VanillaDensityGraphBatch.Sample(0, 0, 0)
        ));

        double[] thresholds = new double[128];
        List<DensityFunction> branches = new ArrayList<>(129);
        for (int i = 0; i < thresholds.length + 1; i++) {
            if (i < thresholds.length) thresholds[i] = i;
            branches.add(DensityFunctions.constant(i));
        }
        DensityFunction branchHeavy = DensityFunctions.intervalSelect(
            DensityFunctions.constant(-1.0), new DoubleArrayList(thresholds), branches
        );
        VanillaDensityGraph.Snapshot branchHeavySnapshot = VanillaDensityGraph.snapshot(branchHeavy);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.input(branchHeavySnapshot, large));
    }

    @Test
    void validatorRejectsCoordinatesOutsideTheW5ProfileDomain() {
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            DensityFunctions.constant(0.0), DensityFunctions.constant(0.0), 100.0,
            Holder.direct(NormalNoise.create(RandomSource.create(0xabcL), -1, 1.0).parameters())
        );
        NormalNoise normal = NormalNoise.create(RandomSource.create(0xdefL), -1, 1.0);
        DensityFunction initialized = initialize(shifted, normal);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(initialized);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.input(
            snapshot, new VanillaDensityGraphBatch.Sample(30_000_000, 0, 30_000_000)
        ));
    }

    @Test
    void validatorDoesNotTrustForgedFiniteBoundsForShiftedNoiseCoordinates() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x5a17L), -1, 1.0);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            DensityFunctions.constant(1.0), DensityFunctions.constant(1.0), 0.0, holder
        );
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(initialize(shifted, normal));
        int[] forged = VanillaDensityGraphBatch.input(snapshot, new VanillaDensityGraphBatch.Sample(0, 0, 0));

        int constantNode = -1;
        for (int node = 0; node < forged[4]; node++) {
            int record = forged[11] + node * VanillaDensityGraphBatch.NODE_WORDS;
            if (forged[record] == 0) {
                constantNode = node;
                break;
            }
        }
        if (constantNode < 0) throw new AssertionError("expected a serialized constant shift node");
        int record = forged[11] + constantNode * VanillaDensityGraphBatch.NODE_WORDS;
        int bounds = forged[13] + constantNode * 4;
        putDouble(forged, record + 10, 1_000_000_000.0);
        putDouble(forged, bounds, 0.0);
        putDouble(forged, bounds + 2, 0.0);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(forged));
    }

    @Test
    void validatorUsesSquareValueRangeInsteadOfVanillaMetadataForShiftedCoordinates() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x5a18L), -1, 1.0);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction squareShift = DensityFunctions.yClampedGradient(0, 1, 0.25, 0.5).square();
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            squareShift, DensityFunctions.constant(0.0), 100.0, holder
        );
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(initialize(shifted, normal));
        VanillaDensityGraphBatch.Sample sample = new VanillaDensityGraphBatch.Sample(-10_000_000, 0, 0);
        int[] malformed = VanillaDensityGraphBatch.input(snapshot, sample);
        int rootRecord = malformed[11] + malformed[5] * VanillaDensityGraphBatch.NODE_WORDS;
        assertEquals(11, malformed[rootRecord], "the root must use the shifted-noise wire opcode");
        putDouble(malformed, rootRecord + 10, 100.00000001);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(malformed));
    }

    @Test
    void validatorIncludesRoundedYGradientCancellationInShiftedCoordinates() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x5a19L), -1, 1.0);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction gradient = DensityFunctions.yClampedGradient(0, 1, 1.0e30, 0.1);
        DensityFunction shift = DensityFunctions.min(gradient, DensityFunctions.constant(0.1));
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            shift, DensityFunctions.constant(0.0), 100.0, holder
        );
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(initialize(shifted, normal));
        VanillaDensityGraphBatch.Sample sample = new VanillaDensityGraphBatch.Sample(-10_000_000, 1, 0);
        double vanillaShift = shift.compute(new DensityFunction.SinglePointContext(sample.x(), sample.y(), sample.z()));
        assertRawEquals(0.0, vanillaShift, "rounded clamped lerp at alpha 1");

        int[] malformed = VanillaDensityGraphBatch.input(snapshot, sample);
        int rootRecord = malformed[11] + malformed[5] * VanillaDensityGraphBatch.NODE_WORDS;
        assertEquals(11, malformed[rootRecord], "the root must use the shifted-noise wire opcode");
        putDouble(malformed, rootRecord + 10, 100.000000005);
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(malformed));
    }

    @Test
    void validatorRejectsEqualYGradientEndpointsEvenWithFiniteDeclaredBounds() {
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(
            DensityFunctions.yClampedGradient(-1, 1, -0.25, 0.75)
        );
        int[] malformed = VanillaDensityGraphBatch.input(snapshot, new VanillaDensityGraphBatch.Sample(0, 0, 0));
        int record = malformed[11];
        malformed[record + 7] = malformed[record + 6];
        assertThrows(IllegalArgumentException.class, () -> VanillaDensityGraphBatch.validate(malformed));
    }

    private static DensityFunction initialize(final DensityFunction function, final NormalNoise noise) {
        return function.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(final DensityFunction input) { return input; }
            @Override public DensityFunction.NoiseHolder visitNoise(final DensityFunction.NoiseHolder input) {
                return new DensityFunction.NoiseHolder(input.noiseData(), noise);
            }
        });
    }

    private static void assertRawEquals(final double expected, final double actual, final String message) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
    }

    private static void putDouble(final int[] words, final int offset, final double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int)bits;
        words[offset + 1] = (int)(bits >>> 32);
    }
}
