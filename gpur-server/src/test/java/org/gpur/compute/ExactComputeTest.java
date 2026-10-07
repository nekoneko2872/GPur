package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExactComputeTest {
    @Test void retainsFarCoordinateDistanceBoundaryAndTies() {
        int[] result = ExactCompute.reference(ExactCompute.distances(30_000_000, 0, 0,
            new double[]{29_999_975.99, 0, 0, 29_999_976.01, 0, 0, 30_000_024, 0, 0}));
        assertTrue(ExactCompute.getDouble(result, 0) > 576);
        assertTrue(ExactCompute.getDouble(result, 2) < 576);
        assertEquals(576, ExactCompute.getDouble(result, 4));
    }

    @Test void transparencyAcrossEveryHaloFaceExposesBoundaryOre() {
        int[] input = new int[2 + ExactCompute.SECTION_WORDS];
        input[0] = 2;
        input[1] = 4096;
        java.util.Arrays.fill(input, 2, 4098, 3);
        assertEquals(1, ExactCompute.reference(input)[0]);
        int[] blocks = {0, 15, 0, 240, 0, 3840};
        int[] faces = {-1, 1, -18, 18, -324, 324};
        for (int i = 0; i < faces.length; i++) {
            int block = blocks[i];
            int center = 4098 + ((block >> 8) + 1) * 324 + (((block >> 4) & 15) + 1) * 18 + (block & 15) + 1;
            input[center + faces[i]] = 1;
            assertEquals(0, ExactCompute.reference(input)[block]);
            input[center + faces[i]] = 0;
        }
    }
}
