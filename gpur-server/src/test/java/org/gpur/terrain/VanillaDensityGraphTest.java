package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
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

    private static void assertRawEquals(final double expected, final double actual, final String message) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
    }
}
