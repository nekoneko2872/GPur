package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VanillaDensityGraphTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();
    }

    @Test
    void exactSubsetMatchesVanillaAcrossCoordinatesAndBranches() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x7d1eL), -2, 0.75, 1.0, 0.375, 0.5);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction y = DensityFunctions.yClampedGradient(-64, 320, -0.3, 0.7);
        DensityFunction noise = DensityFunctions.noise(holder, 0.125, 0.0625);
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            DensityFunctions.shift(holder), DensityFunctions.shiftA(holder), 0.03125, holder
        );
        DensityFunction branch = DensityFunctions.rangeChoice(
            y,
            -0.1,
            0.2,
            shifted,
            DensityFunctions.add(
                DensityFunctions.shiftB(holder),
                DensityFunctions.add(
                    DensityFunctions.mul(noise, DensityFunctions.constant(1.25)),
                    DensityFunctions.add(noise, DensityFunctions.constant(-0.125)).abs().cube().squeeze().clamp(-1.0, 1.0)
                )
            )
        );
        DensityFunction intervals = DensityFunctions.intervalSelect(
            y,
            new DoubleArrayList(new double[] {-0.3, -0.1, 0.0, 0.2}),
            List.of(noise, DensityFunctions.shift(holder), shifted, branch, noise.quarterNegative())
        );
        DensityFunction root = DensityFunctions.min(
            DensityFunctions.max(DensityFunctions.add(intervals, y), DensityFunctions.mul(branch, shifted)),
            DensityFunctions.constant(0.75)
        );
        root = initialize(root, normal);

        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        assertTrue(snapshot.nodeCount() > 8);
        assertTrue(snapshot.edgeCount() >= snapshot.nodeCount() - 1);
        assertEquals(1, snapshot.noiseProfileCount(), "all leaves share the initialized sampler profile by identity");

        int[][] points = {
            {-30_000_000, -65, 30_000_000}, {-31, -64, 47}, {-1, -1, -1},
            {0, 0, 0}, {73, 64, -911}, {17_000_000, 320, -19_000_000}, {13, 321, -5}
        };
        for (int[] point : points) {
            double expected = root.compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
            double actual = snapshot.evaluate(point[0], point[1], point[2]);
            assertRawEquals(expected, actual, "point " + List.of(point[0], point[1], point[2]));
        }
    }

    @Test
    void rangeAndIntervalBranchesAreLazyAndUseVanillaStrictComparisons() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x43e9L), -1, 1.0, 0.5, 0.25);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction inRange = DensityFunctions.noise(holder, 0.125, 0.25);
        DensityFunction outOfRange = DensityFunctions.noise(holder, 0.75, 0.5);
        DensityFunction range = initialize(
            DensityFunctions.rangeChoice(DensityFunctions.constant(0.0), 0.0, 1.0, inRange, outOfRange), normal
        );
        VanillaDensityGraph.Snapshot rangeSnapshot = VanillaDensityGraph.snapshot(range);

        List<Double> sampledX = new ArrayList<>();
        VanillaDensityGraph.NoiseSampler recorder = (profile, x, y, z) -> {
            sampledX.add(x);
            return x;
        };
        assertRawEquals(2.0, rangeSnapshot.evaluate(16, 4, -8, recorder), "minimum is inclusive");
        assertEquals(List.of(2.0), sampledX, "out-of-range branch was not evaluated");

        sampledX.clear();
        DensityFunction upperExclusive = initialize(
            DensityFunctions.rangeChoice(DensityFunctions.constant(1.0), 0.0, 1.0, inRange, outOfRange), normal
        );
        assertRawEquals(12.0, VanillaDensityGraph.snapshot(upperExclusive).evaluate(16, 4, -8, recorder), "maximum is exclusive");
        assertEquals(List.of(12.0), sampledX, "in-range branch was not evaluated");

        DensityFunction intervals = DensityFunctions.intervalSelect(
            DensityFunctions.constant(0.0),
            new DoubleArrayList(new double[] {0.0, 1.0}),
            List.of(DensityFunctions.constant(10.0), DensityFunctions.constant(20.0), DensityFunctions.constant(30.0))
        );
        VanillaDensityGraph.Snapshot intervalSnapshot = VanillaDensityGraph.snapshot(intervals);
        assertRawEquals(20.0, intervalSnapshot.evaluate(0, 0, 0), "threshold equality selects the following interval");
        DensityFunction finalInterval = DensityFunctions.intervalSelect(
            DensityFunctions.constant(1.0),
            new DoubleArrayList(new double[] {0.0, 1.0}),
            List.of(DensityFunctions.constant(10.0), DensityFunctions.constant(20.0), DensityFunctions.constant(30.0))
        );
        assertRawEquals(30.0, VanillaDensityGraph.snapshot(finalInterval).evaluate(0, 0, 0), "last interval is the default branch");

        DensityFunction lazyInterval = initialize(
            DensityFunctions.intervalSelect(
                DensityFunctions.constant(0.0),
                new DoubleArrayList(new double[] {0.0}),
                List.of(inRange, outOfRange)
            ),
            normal
        );
        sampledX.clear();
        assertRawEquals(12.0, VanillaDensityGraph.snapshot(lazyInterval).evaluate(16, 4, -8, recorder), "interval threshold equality selects the next branch");
        assertEquals(List.of(12.0), sampledX, "unselected interval function was not evaluated");
    }

    @Test
    void arithmeticShortCircuitsAndEveryMappedUnaryOperationMatchVanilla() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x3a17L), -1, 1.0, 0.5, 0.25);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction noise = initialize(DensityFunctions.noise(holder, 0.25, 0.5), normal);
        int[] sampleCalls = {0};
        VanillaDensityGraph.NoiseSampler recorder = (profile, x, y, z) -> {
            sampleCalls[0]++;
            return 0.125;
        };

        DensityFunction zero = DensityFunctions.yClampedGradient(-32, 32, 0.0, 0.0);
        DensityFunction zeroTimesNoise = DensityFunctions.mul(zero, noise);
        assertRawEquals(0.0, VanillaDensityGraph.snapshot(zeroTimesNoise).evaluate(3, -7, 11, recorder), "vanilla multiply emits positive zero");
        assertEquals(0, sampleCalls[0], "zero multiplier skips its second argument");
        DensityFunction belowNoiseMinimum = DensityFunctions.min(DensityFunctions.constant(-1.0e6), noise);
        assertRawEquals(-1.0e6, VanillaDensityGraph.snapshot(belowNoiseMinimum).evaluate(3, -7, 11, recorder), "minimum bounds skip the second argument");
        assertEquals(0, sampleCalls[0]);
        DensityFunction aboveNoiseMaximum = DensityFunctions.max(DensityFunctions.constant(1.0e6), noise);
        assertRawEquals(1.0e6, VanillaDensityGraph.snapshot(aboveNoiseMaximum).evaluate(3, -7, 11, recorder), "maximum bounds skip the second argument");
        assertEquals(0, sampleCalls[0]);

        DensityFunction constant = DensityFunctions.constant(-0.75);
        List<DensityFunction> mapped = List.of(
            constant.abs(), constant.square(), constant.cube(), constant.halfNegative(),
            constant.quarterNegative(), constant.invert(), constant.squeeze()
        );
        for (DensityFunction function : mapped) {
            VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(function);
            double expected = function.compute(new DensityFunction.SinglePointContext(-17, 19, 23));
            assertRawEquals(expected, snapshot.evaluate(-17, 19, 23), "mapped " + function);
        }
    }

    @Test
    void initializedBlendedNoiseProfileMatchesTheVanillaDensityFunction() {
        BlendedNoise blended = new BlendedNoise(RandomSource.create(0x4b13L), 1.0, 1.0, 8.55515, 4.277575, 2.0);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(blended);
        assertEquals(1, snapshot.noiseProfileCount());
        int[][] points = {{-187, -53, 91}, {-16, 0, 16}, {0, -64, 0}, {243, 311, -77}};
        for (int[] point : points) {
            double expected = blended.compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
            assertRawEquals(expected, snapshot.evaluate(point[0], point[1], point[2]), "blended noise point");
        }
    }

    @Test
    void sharedDensityIdentityIsStoredOnceAndGraphBoundsRejectBeforeSnapshot() {
        DensityFunction shared = DensityFunctions.yClampedGradient(-32, 48, -2.0, 3.0);
        DensityFunction root = DensityFunctions.add(shared, shared);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        assertEquals(2, snapshot.nodeCount(), "the repeated child is represented once by object identity");
        assertEquals(2, snapshot.edgeCount());

        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(root, new VanillaDensityGraph.Limits(1, 1, 8, 0, 4))
        );
    }

    @Test
    void integratedNestedSplineMatchesVanillaRawBitsAcrossIndependentNoiseCoordinates() {
        NormalNoise normalA = NormalNoise.create(RandomSource.create(0x5a31L), -2, 0.75, 1.0, 0.375, 0.5);
        NormalNoise normalB = NormalNoise.create(RandomSource.create(0x7c29L), -1, 1.0, 0.5, 0.25);
        DensityFunction coordinateAFunction = initialize(
            DensityFunctions.add(
                DensityFunctions.noise(Holder.direct(normalA.parameters()), 0.125, 0.0625),
                DensityFunctions.yClampedGradient(-64, 64, -0.5, 0.5)
            ),
            normalA
        );
        DensityFunction coordinateBFunction = initialize(
            DensityFunctions.noise(Holder.direct(normalB.parameters()), 0.0625, 0.125), normalB
        );
        DensityFunctions.Spline.Coordinate coordinateA = new DensityFunctions.Spline.Coordinate(coordinateAFunction);
        DensityFunctions.Spline.Coordinate coordinateB = new DensityFunctions.Spline.Coordinate(coordinateBFunction);
        CubicSpline<DensityFunctions.Spline.Coordinate> inner = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(coordinateB)
            .addPoint(-1.0F, -0.75F, 0.125F)
            .addPoint(0.0F, 0.25F, -0.375F)
            .addPoint(1.0F, 0.875F, 0.25F)
            .build();
        DensityFunctions.Spline spline = new DensityFunctions.Spline(
            new CubicSpline.Multipoint<>(
                coordinateA,
                new float[] {-1.0F, 0.0F, 1.0F},
                List.of(CubicSpline.constant(-0.5F), inner, CubicSpline.constant(0.75F)),
                new float[] {0.125F, 0.375F, -0.25F}
            )
        );
        DensityFunction root = DensityFunctions.add(spline, spline);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        assertEquals(6, snapshot.splinePointCount());
        assertEquals(2, snapshot.noiseProfileCount());

        int[][] positions = {
            {-30_000_000, -64, 30_000_000}, {-911, -31, 47}, {-1, -1, -1}, {0, 0, 0},
            {73, 32, -911}, {17_000_000, 64, -19_000_000}, {13, 65, -5}, {12_345, 127, -67_890}
        };
        for (int[] position : positions) {
            DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(position[0], position[1], position[2]);
            assertRawEquals(root.compute(context), snapshot.evaluate(position[0], position[1], position[2]),
                "integrated spline position " + List.of(position[0], position[1], position[2]));
        }
    }

    @Test
    void integratedSplinePreservesFloatSignedZeroAndCancellationOrder() {
        DensityFunctions.Spline.Coordinate belowFirst = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(-1.0));
        DensityFunctions.Spline signedZero = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(belowFirst)
                .addPoint(0.0F, -0.0F, -0.0F)
                .addPoint(1.0F, 1.0F, 0.0F)
                .build()
        );
        VanillaDensityGraph.Snapshot signedZeroSnapshot = VanillaDensityGraph.snapshot(signedZero);
        assertRawEquals(
            signedZero.compute(new DensityFunction.SinglePointContext(0, 0, 0)), signedZeroSnapshot.evaluate(0, 0, 0),
            "zero derivative preserves the endpoint negative zero"
        );

        DensityFunctions.Spline.Coordinate cancellationPoint = new DensityFunctions.Spline.Coordinate(
            DensityFunctions.constant(0.3333333432674408)
        );
        DensityFunctions.Spline cancellation = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(cancellationPoint)
                .addPoint(-1.0F, 16_777_216.0F, 0.125F)
                .addPoint(1.0F, -16_777_216.0F, -0.25F)
                .build()
        );
        VanillaDensityGraph.Snapshot cancellationSnapshot = VanillaDensityGraph.snapshot(cancellation);
        assertRawEquals(
            cancellation.compute(new DensityFunction.SinglePointContext(0, 0, 0)), cancellationSnapshot.evaluate(0, 0, 0),
            "spline interpolation preserves binary32 cancellation order"
        );
    }

    @Test
    void splineEndpointEvaluationDoesNotVisitUnselectedSplineCoordinates() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x9137L), -1, 1.0, 0.5);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction noiseCoordinateFunction = initialize(DensityFunctions.noise(holder, 0.25, 0.5), normal);
        DensityFunctions.Spline.Coordinate noiseCoordinate = new DensityFunctions.Spline.Coordinate(noiseCoordinateFunction);
        CubicSpline<DensityFunctions.Spline.Coordinate> unselectedSpline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(noiseCoordinate)
            .addPoint(0.0F, 0.25F)
            .addPoint(1.0F, 0.75F)
            .build();
        DensityFunctions.Spline.Coordinate belowFirst = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(-1.0));
        DensityFunctions.Spline root = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(belowFirst)
                .addPoint(0.0F, 0.125F)
                .addPoint(1.0F, unselectedSpline)
                .build()
        );
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        int[] noiseCalls = {0};
        double actual = snapshot.evaluate(9, -3, 11, (profile, x, y, z) -> {
            noiseCalls[0]++;
            return 0.0;
        });
        assertRawEquals(root.compute(new DensityFunction.SinglePointContext(9, -3, 11)), actual, "first endpoint branch");
        assertEquals(0, noiseCalls[0], "the unselected spline coordinate is not evaluated");
    }

    @Test
    void splineArraysAreCopiedAndSharedSplineNodesUseGlobalIdentity() {
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(0.5));
        float[] locations = {0.0F, 1.0F};
        float[] derivatives = {0.125F, -0.25F};
        List<CubicSpline<DensityFunctions.Spline.Coordinate>> pointValues = new ArrayList<>();
        pointValues.add(CubicSpline.constant(-0.5F));
        pointValues.add(CubicSpline.constant(0.75F));
        CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> multipoint =
            new CubicSpline.Multipoint<>(coordinate, locations, pointValues, derivatives);
        DensityFunctions.Spline spline = new DensityFunctions.Spline(multipoint);
        DensityFunction root = DensityFunctions.add(spline, spline);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        assertEquals(2, snapshot.splinePointCount());
        assertEquals(6, snapshot.edgeCount(), "shared spline wrapper and multipoint nodes keep one global identity");
        assertEquals(6, snapshot.nodeCount());

        double expected = root.compute(new DensityFunction.SinglePointContext(2, 0, -9));
        locations[0] = 0.75F;
        derivatives[1] = 100.0F;
        pointValues.set(0, CubicSpline.constant(12.0F));
        VanillaDensityGraph.Instruction splineInstruction = snapshot.instructions.stream()
            .filter(instruction -> instruction.op() instanceof VanillaDensityGraph.SplineMultipoint)
            .findFirst().orElseThrow();
        VanillaDensityGraph.SplineMultipoint captured = (VanillaDensityGraph.SplineMultipoint)splineInstruction.op();
        assertRawEquals((double)multipoint.minValue(), splineInstruction.minValue(), "Mache spline minimum metadata is retained");
        assertRawEquals((double)multipoint.maxValue(), splineInstruction.maxValue(), "Mache spline maximum metadata is retained");
        float[] exposedLocations = captured.locations();
        exposedLocations[0] = 99.0F;
        assertRawEquals(expected, snapshot.evaluate(2, 0, -9), "snapshot owns spline arrays and point child IDs");
    }

    @Test
    void repeatedSplineChildrenShareEvaluationWork() {
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x31b7L), -1, 1.0, 0.5);
        DensityFunction coordinateFunction = initialize(
            DensityFunctions.noise(Holder.direct(normal.parameters()), 0.125, 0.25), normal
        );
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(coordinateFunction);
        CubicSpline<DensityFunctions.Spline.Coordinate> repeated = CubicSpline.constant(0.25F);
        for (int i = 0; i < 12; i++) {
            repeated = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(coordinate)
                .addPoint(0.0F, repeated)
                .addPoint(1.0F, repeated)
                .build();
        }

        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(new DensityFunctions.Spline(repeated));
        assertEquals(15, snapshot.nodeCount());
        assertEquals(37, snapshot.edgeCount());
        assertEquals(24, snapshot.splinePointCount());
        int[] noiseCalls = {0};
        double actual = snapshot.evaluate(0, 0, 0, (profile, x, y, z) -> {
            noiseCalls[0]++;
            return 0.5;
        });
        assertRawEquals(0.25, actual, "repeated shared spline children preserve the sample");
        assertEquals(1, noiseCalls[0], "shared coordinate work is evaluated once per sample");
    }

    @Test
    void cache2dBindingRequiresExactWrapperAndCompleteYIndependentProof() throws ReflectiveOperationException {
        DensityFunction wrapped = DensityFunctions.add(DensityFunctions.constant(0.25), DensityFunctions.constant(0.5));
        DensityFunction cache2d = newCache2D(wrapped);
        VanillaDensityGraph.WrapperBindings bindings = VanillaDensityGraph.WrapperBindings.builder().bindCache2D(cache2d).build();
        assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(cache2d));
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(cache2d, VanillaDensityGraph.Limits.DEFAULT, bindings);
        DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(12, -51, -20);
        assertRawEquals(cache2d.compute(context), snapshot.evaluate(12, -51, -20), "Cache2D aliases its proven Y-independent child");
        VanillaDensityGraph.Instruction rootInstruction = snapshot.instructions.get(snapshot.root);
        assertRawEquals(cache2d.minValue(), rootInstruction.minValue(), "Cache2D keeps its original lower metadata");
        assertRawEquals(cache2d.maxValue(), rootInstruction.maxValue(), "Cache2D keeps its original upper metadata");

        DensityFunction yDependent = DensityFunctions.yClampedGradient(-32, 48, -2.0, 3.0);
        DensityFunction yDependentCache = newCache2D(yDependent);
        VanillaDensityGraph.WrapperBindings yBindings = VanillaDensityGraph.WrapperBindings.builder().bindCache2D(yDependentCache).build();
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(yDependentCache, VanillaDensityGraph.Limits.DEFAULT, yBindings)
        );

        DensityFunctions.Spline.Coordinate yCoordinate = new DensityFunctions.Spline.Coordinate(yDependent);
        CubicSpline<DensityFunctions.Spline.Coordinate> coordinateSpline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(yCoordinate)
            .addPoint(-1.0F, 0.0F)
            .addPoint(1.0F, 1.0F)
            .build();
        DensityFunction coordinateSplineCache = newCache2D(new DensityFunctions.Spline(coordinateSpline));
        VanillaDensityGraph.WrapperBindings coordinateBindings = VanillaDensityGraph.WrapperBindings.builder()
            .bindCache2D(coordinateSplineCache).build();
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(coordinateSplineCache, VanillaDensityGraph.Limits.DEFAULT, coordinateBindings)
        );

        DensityFunctions.Spline.Coordinate constantCoordinate = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(0.0));
        CubicSpline<DensityFunctions.Spline.Coordinate> yDependentValue = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(yCoordinate)
            .addPoint(-1.0F, -0.5F)
            .addPoint(1.0F, 0.5F)
            .build();
        CubicSpline<DensityFunctions.Spline.Coordinate> valueSpline = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(constantCoordinate)
            .addPoint(-1.0F, yDependentValue)
            .addPoint(1.0F, yDependentValue)
            .build();
        DensityFunction valueSplineCache = newCache2D(new DensityFunctions.Spline(valueSpline));
        VanillaDensityGraph.WrapperBindings valueBindings = VanillaDensityGraph.WrapperBindings.builder()
            .bindCache2D(valueSplineCache).build();
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(valueSplineCache, VanillaDensityGraph.Limits.DEFAULT, valueBindings)
        );

        DensityFunction serializedMarker = marker("Cache2D", wrapped);
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.WrapperBindings.builder().bindCache2D(serializedMarker)
        );
    }

    @Test
    void cacheOnceAndFlatCacheBindingsRejectNonNoiseChunkMarkers() {
        DensityFunction wrapped = DensityFunctions.constant(0.75);
        DensityFunction cacheOnceMarker = marker("CacheOnce", wrapped);
        DensityFunction flatCacheMarker = marker("FlatCache", wrapped);
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.WrapperBindings.builder().bindCacheOnceForCornerPreparation(cacheOnceMarker)
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.WrapperBindings.builder().bindFlatCache(flatCacheMarker, -2, 3, 1, new double[] {0.75})
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new VanillaDensityGraph.Limits(32, 32, 16, 0, 8, 32, 4_097)
        );
    }

    @Test
    void splineCyclesUnknownCoordinatesAndUnifiedResourceLimitsFailClosed() {
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(0.0));
        List<CubicSpline<DensityFunctions.Spline.Coordinate>> mutableValues = new ArrayList<>();
        mutableValues.add(CubicSpline.constant(0.5F));
        CubicSpline.Multipoint<DensityFunctions.Spline.Coordinate> cyclic =
            new CubicSpline.Multipoint<>(coordinate, new float[] {0.0F}, mutableValues, new float[] {0.0F});
        mutableValues.set(0, cyclic);
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(new DensityFunctions.Spline(cyclic))
        );

        DensityFunction unknown = new DensityFunction() {
            @Override public double compute(final FunctionContext context) { throw new AssertionError("snapshot must not compute an unknown spline coordinate"); }
            @Override public void fillArray(final double[] output, final ContextProvider provider) { throw new AssertionError("snapshot must not fill an unknown spline coordinate"); }
            @Override public DensityFunction mapChildren(final Visitor visitor) { throw new AssertionError("snapshot must not visit an unknown spline coordinate"); }
            @Override public double minValue() { return 0.0; }
            @Override public double maxValue() { return 1.0; }
            @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
        };
        DensityFunctions.Spline.Coordinate unknownCoordinate = new DensityFunctions.Spline.Coordinate(unknown);
        DensityFunctions.Spline unsupported = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(unknownCoordinate).addPoint(0.0F, 1.0F).build()
        );
        assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(unsupported));

        DensityFunctions.Spline.Coordinate constant = new DensityFunctions.Spline.Coordinate(DensityFunctions.constant(0.5));
        CubicSpline<DensityFunctions.Spline.Coordinate> shared = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(constant)
            .addPoint(0.0F, CubicSpline.constant(0.25F))
            .build();
        CubicSpline<DensityFunctions.Spline.Coordinate> nested = CubicSpline.<DensityFunctions.Spline.Coordinate>builder(constant)
            .addPoint(0.0F, shared)
            .build();
        DensityFunctions.Spline deepShared = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(constant)
                .addPoint(0.0F, shared)
                .addPoint(1.0F, nested)
                .build()
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(deepShared, new VanillaDensityGraph.Limits(32, 32, 3, 0, 8, 32))
        );

        DensityFunctions.Spline threePoints = new DensityFunctions.Spline(
            CubicSpline.<DensityFunctions.Spline.Coordinate>builder(constant)
                .addPoint(-1.0F, 0.0F)
                .addPoint(0.0F, 0.5F)
                .addPoint(1.0F, 1.0F)
                .build()
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(threePoints, new VanillaDensityGraph.Limits(32, 32, 16, 0, 2, 8))
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(threePoints, new VanillaDensityGraph.Limits(32, 32, 16, 0, 8, 2))
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(threePoints, new VanillaDensityGraph.Limits(2, 32, 16, 0, 8, 8))
        );
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(threePoints, new VanillaDensityGraph.Limits(32, 1, 16, 0, 8, 8))
        );
    }

    @Test
    void unknownUninitializedCyclicAndTooDeepGraphsFailClosed() {
        DensityFunction unknown = new DensityFunction() {
            @Override public double compute(final FunctionContext context) { throw new AssertionError("snapshot must not compute unknown nodes"); }
            @Override public void fillArray(final double[] output, final ContextProvider provider) { throw new AssertionError("snapshot must not fill unknown nodes"); }
            @Override public DensityFunction mapChildren(final Visitor visitor) { throw new AssertionError("snapshot must not visit unknown nodes"); }
            @Override public double minValue() { throw new AssertionError("snapshot must not query unknown bounds"); }
            @Override public double maxValue() { throw new AssertionError("snapshot must not query unknown bounds"); }
            @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
        };
        assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(unknown));

        NormalNoise normal = NormalNoise.create(RandomSource.create(0x4f19L), -1, 1.0);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(normal.parameters());
        DensityFunction uninitialized = DensityFunctions.noise(holder, 0.5, 0.25);
        assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(uninitialized));

        List<DensityFunction> branches = new ArrayList<>();
        DensityFunction cycle = DensityFunctions.intervalSelect(
            DensityFunctions.constant(0.0), new DoubleArrayList(new double[] {0.0}), branches
        );
        branches.add(cycle);
        branches.add(DensityFunctions.constant(1.0));
        assertThrows(VanillaDensityGraph.UnsupportedGraphException.class, () -> VanillaDensityGraph.snapshot(cycle));

        DensityFunction tooDeep = DensityFunctions.constant(0.0);
        for (int i = 0; i < 5; i++) tooDeep = tooDeep.abs();
        DensityFunction boundedDepthGraph = tooDeep;
        assertThrows(
            VanillaDensityGraph.UnsupportedGraphException.class,
            () -> VanillaDensityGraph.snapshot(boundedDepthGraph, new VanillaDensityGraph.Limits(20, 20, 3, 0, 4))
        );
    }

    private static DensityFunction initialize(final DensityFunction function, final NormalNoise noise) {
        return function.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(final DensityFunction input) { return input; }
            @Override public DensityFunction.NoiseHolder visitNoise(final DensityFunction.NoiseHolder input) {
                return new DensityFunction.NoiseHolder(input.noiseData(), noise);
            }
        });
    }

    private static DensityFunction newCache2D(final DensityFunction wrapped) throws ReflectiveOperationException {
        Class<?> cache2DType = Class.forName(NoiseChunk.class.getName() + "$Cache2D");
        var constructor = cache2DType.getDeclaredConstructor(DensityFunction.class);
        if (!constructor.trySetAccessible()) throw new IllegalStateException("cannot access NoiseChunk.Cache2D test constructor");
        return (DensityFunction)constructor.newInstance(wrapped);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DensityFunction marker(final String markerName, final DensityFunction wrapped) {
        try {
            Class<?> markerType = Class.forName(DensityFunctions.class.getName() + "$Marker$Type");
            Enum<?> enumValue = Enum.valueOf((Class)markerType, markerName);
            Class<?> markerClass = Class.forName(DensityFunctions.class.getName() + "$Marker");
            Constructor<?> constructor = markerClass.getDeclaredConstructor(markerType, DensityFunction.class);
            if (!constructor.trySetAccessible()) throw new ReflectiveOperationException("cannot access vanilla marker constructor");
            return (DensityFunction)constructor.newInstance(enumValue, wrapped);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot construct vanilla marker fixture", exception);
        }
    }

    private static void assertRawEquals(final double expected, final double actual, final String message) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
    }
}
