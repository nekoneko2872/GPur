package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExactStartupCorpusTest {
    @Test
    void corpusContainsBoundedValidatedWorkloadsForEachSupportedWidthAndHeight() {
        List<int[]> workloads = ExactStartupCorpus.workloads();
        assertEquals(8, workloads.size());
        boolean[] widths = new boolean[17];
        boolean[] heights = new boolean[33];
        for (int[] input : workloads) {
            ExactCompute.validate(input);
            assertEquals(VanillaTerrainInterpolation.WORKLOAD, input[0]);
            widths[input[2]] = true;
            heights[input[3]] = true;
            assertTrue(VanillaTerrainInterpolation.outputWords(input) <= 8192 * 2);
        }
        for (int width : new int[]{1, 2, 4, 8, 16}) assertTrue(widths[width], "missing width " + width);
        for (int height : new int[]{1, 2, 4, 8, 16, 32}) assertTrue(heights[height], "missing height " + height);
    }

    @Test
    void corpusInputsExerciseSignedZeroSubnormalsNearZeroAndCancellation() {
        boolean positiveZero = false;
        boolean negativeZero = false;
        boolean subnormal = false;
        boolean nearZero = false;
        boolean highMagnitude = false;
        for (int[] input : ExactStartupCorpus.workloads()) {
            for (int offset = VanillaTerrainInterpolation.HEADER_WORDS; offset < input.length; offset += 2) {
                double value = ExactCompute.getDouble(input, offset);
                long bits = Double.doubleToRawLongBits(value);
                positiveZero |= bits == Double.doubleToRawLongBits(0.0);
                negativeZero |= bits == Double.doubleToRawLongBits(-0.0);
                subnormal |= value != 0.0 && Math.abs(value) < Double.MIN_NORMAL;
                nearZero |= value != 0.0 && Math.abs(value) <= 1.0e-100;
                highMagnitude |= Math.abs(value) >= 1.0e6 - 0.125;
            }
        }
        assertTrue(positiveZero);
        assertTrue(negativeZero);
        assertTrue(subnormal);
        assertTrue(nearZero);
        assertTrue(highMagnitude);
    }

    @Test
    void returnedWorkloadsCannotMutateTheSharedCorpus() {
        int[] firstRead = ExactStartupCorpus.workloads().getFirst();
        int originalHeader = firstRead[0];
        firstRead[0] = -1;
        assertEquals(originalHeader, ExactStartupCorpus.workloads().getFirst()[0]);
        assertThrows(UnsupportedOperationException.class, () -> ExactStartupCorpus.workloads().clear());
    }
}
