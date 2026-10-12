package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.gpur.terrain.VanillaCornerGraphFrame;
import org.gpur.terrain.VanillaCornerGraphFrame.Lattice;
import org.gpur.terrain.VanillaCornerGraphFrame.Part;
import org.junit.jupiter.api.Test;

/** Tests numeric ownership and refusal boundaries; these are not live GPU adoption tests. */
class VanillaCornerGraphFrameTest {
    @Test
    void completePartsFillEveryColumnWithExactBitsAndCountOnlyConsumption() {
        Lattice lattice = new Lattice(-4, -8, 7, 4, 8, 2, 3);
        Object owner = new Object();
        DensityFunction root = mock(DensityFunction.class);
        Owned first = owned(0, 16);
        Owned second = owned(16, 20);
        try (VanillaCornerGraphFrame frame = VanillaCornerGraphFrame.take(owner, root, lattice,
            List.of(new Part(0, first.result), new Part(16, second.result)))) {
            assertNotNull(frame);
            assertEquals(0, first.consumed.get() + second.consumed.get());
            for (int x = 0; x <= lattice.cellsXZ(); x++) {
                for (int z = 0; z <= lattice.cellsXZ(); z++) {
                    double[] output = new double[lattice.columnHeight()];
                    assertTrue(frame.tryFillColumn(owner, root, lattice.firstCellX() + x, lattice.firstCellZ() + z, output));
                    for (int y = 0; y < output.length; y++) {
                        int sample = (x * (lattice.cellsXZ() + 1) + z) * lattice.columnHeight() + y;
                        assertRawEquals(value(sample), output[y]);
                    }
                }
            }
            assertEquals(16, first.consumed.get());
            assertEquals(20, second.consumed.get());
        }
        assertEquals(1, first.releases.get());
        assertEquals(1, second.releases.get());
    }

    @Test
    void wrongOwnerRootCoordinatesAndColumnLengthLeaveOutputUnchanged() {
        Object owner = new Object();
        DensityFunction root = mock(DensityFunction.class);
        Lattice lattice = new Lattice(-4, -8, 7, 4, 8, 2, 3);
        Owned numeric = owned(0, lattice.sampleCount());
        try (VanillaCornerGraphFrame frame = VanillaCornerGraphFrame.take(owner, root, lattice,
            List.of(new Part(0, numeric.result)))) {
            assertNotNull(frame);
            double[] sentinel = {91.0, -0.0, 93.0, 94.0};
            long[] original = bits(sentinel);
            assertFalse(frame.tryFillColumn(new Object(), root, -4, 7, sentinel));
            assertFalse(frame.tryFillColumn(owner, mock(DensityFunction.class), -4, 7, sentinel));
            assertFalse(frame.tryFillColumn(owner, root, -5, 7, sentinel));
            assertFalse(frame.tryFillColumn(owner, root, -1, 7, sentinel));
            assertFalse(frame.tryFillColumn(owner, root, -4, 6, sentinel));
            assertFalse(frame.tryFillColumn(owner, root, -4, 10, sentinel));
            assertFalse(frame.tryFillColumn(owner, root, Integer.MIN_VALUE, Integer.MAX_VALUE, sentinel));
            double[] shortColumn = {7.0, -0.0};
            assertFalse(frame.tryFillColumn(owner, root, -4, 7, shortColumn));
            assertTrue(Arrays.equals(original, bits(sentinel)));
            assertRawEquals(7.0, shortColumn[0]);
            assertRawEquals(-0.0, shortColumn[1]);
            assertEquals(0, numeric.consumed.get());
        }
    }

    @Test
    void staleAndClosedNumericLeasesRefuseWithoutChangingTheSlice() {
        for (boolean retire : new boolean[] {false, true}) {
            Object owner = new Object();
            DensityFunction root = mock(DensityFunction.class);
            Lattice lattice = new Lattice(0, 0, 0, 4, 8, 1, 1);
            Owned numeric = owned(0, lattice.sampleCount());
            VanillaCornerGraphFrame frame = VanillaCornerGraphFrame.take(owner, root, lattice,
                List.of(new Part(0, numeric.result)));
            assertNotNull(frame);
            if (retire) numeric.valid.set(false); else numeric.result.close();
            double[] output = {123.0, -0.0};
            assertFalse(frame.tryFillColumn(owner, root, 0, 0, output));
            assertRawEquals(123.0, output[0]);
            assertRawEquals(-0.0, output[1]);
            assertEquals(0, numeric.consumed.get());
            frame.close();
            frame.close();
            assertEquals(1, numeric.releases.get());
            assertFalse(frame.tryFillColumn(owner, root, 0, 0, output));
        }
    }

    @Test
    void missingMisalignedOverlappingOrStalePartsCloseAllTransferredResults() {
        Lattice lattice = new Lattice(0, 0, 0, 4, 8, 1, 1);
        for (int mode = 0; mode < 6; mode++) {
            Owned first = owned(0, 4);
            Owned second = owned(4, mode == 2 ? 3 : 4);
            if (mode == 3) second.valid.set(false);
            List<Part> parts = switch (mode) {
                case 0 -> List.of(new Part(0, first.result));
                case 1 -> List.of(new Part(0, first.result), new Part(3, second.result));
                case 2, 3 -> List.of(new Part(0, first.result), new Part(4, second.result));
                case 4 -> Arrays.asList(new Part(0, first.result), null, new Part(4, second.result));
                default -> List.of(new Part(0, first.result), new Part(5, second.result));
            };
            assertNull(VanillaCornerGraphFrame.take(new Object(), mock(DensityFunction.class), lattice, parts), "mode=" + mode);
            assertEquals(1, first.releases.get(), "mode=" + mode);
            assertEquals(mode == 0 ? 0 : 1, second.releases.get(), "mode=" + mode);
            second.result.close();
        }
    }

    @Test
    void duplicateFramesNonfiniteOrOddLengthReadbackAreNeverAdmitted() {
        Lattice lattice = new Lattice(0, 0, 0, 4, 8, 1, 1);
        Owned duplicate = owned(0, 4);
        assertNull(VanillaCornerGraphFrame.take(new Object(), mock(DensityFunction.class), lattice,
            List.of(new Part(0, duplicate.result), new Part(4, duplicate.result))));
        assertEquals(1, duplicate.releases.get());
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            int[] output = words(0, lattice.sampleCount());
            long raw = Double.doubleToRawLongBits(bad);
            output[2] = (int)raw;
            output[3] = (int)(raw >>> 32);
            AtomicLong released = new AtomicLong();
            WorldgenResult numeric = new WorldgenResult(output, () -> true, released::incrementAndGet, ignored -> {});
            assertNull(VanillaCornerGraphFrame.take(new Object(), mock(DensityFunction.class), lattice,
                List.of(new Part(0, numeric))));
            assertEquals(1, released.get());
        }
        AtomicLong released = new AtomicLong();
        WorldgenResult odd = new WorldgenResult(new int[15], () -> true, released::incrementAndGet, ignored -> {});
        assertNull(VanillaCornerGraphFrame.take(new Object(), mock(DensityFunction.class), lattice, List.of(new Part(0, odd))));
        assertEquals(1, released.get());
    }

    @Test
    void invalidOwnerArgumentsReleaseTransferredPartsAndLatticeBoundsRejectOverflow() {
        Lattice lattice = new Lattice(0, 0, 0, 4, 8, 1, 1);
        Owned numeric = owned(0, lattice.sampleCount());
        assertThrows(NullPointerException.class, () -> VanillaCornerGraphFrame.take(null, mock(DensityFunction.class),
            lattice, List.of(new Part(0, numeric.result))));
        assertEquals(1, numeric.releases.get());
        assertThrows(IllegalArgumentException.class, () -> new Lattice(Integer.MAX_VALUE, 0, 0, 4, 8, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Lattice(0, Integer.MIN_VALUE, 0, 4, 8, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Lattice(0, 0, 0, 1, 1, 16, 1_024));
        assertThrows(IllegalArgumentException.class, () -> new Lattice(7_500_000, 0, 0, 4, 8, 1, 1));
    }

    private static Owned owned(final int start, final int count) {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicLong releases = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        return new Owned(new WorldgenResult(words(start, count), valid::get, releases::incrementAndGet, consumed::addAndGet),
            valid, releases, consumed);
    }

    private static int[] words(final int start, final int count) {
        int[] words = new int[count * 2];
        for (int i = 0; i < count; i++) {
            long raw = Double.doubleToRawLongBits(value(start + i));
            words[i * 2] = (int)raw;
            words[i * 2 + 1] = (int)(raw >>> 32);
        }
        return words;
    }

    private static double value(final int sample) {
        return sample == 0 ? -0.0 : (sample & 1) == 0 ? -sample * 0.125 : sample * 0.125;
    }

    private static long[] bits(final double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private static void assertRawEquals(final double expected, final double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private record Owned(WorldgenResult result, AtomicBoolean valid, AtomicLong releases, AtomicLong consumed) {}
}
