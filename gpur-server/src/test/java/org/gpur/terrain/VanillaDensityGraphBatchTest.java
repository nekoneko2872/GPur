package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
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
