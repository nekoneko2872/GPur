package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

class VanillaTerrainInterpolationTest {
    @Test
    void keepsVanillaCacheAndBlockInterpolationOrdersDistinct() {
        double[][][] slice0 = slice(1, 5, 2);
        double[][][] slice1 = slice(1, 5, 2);
        double[] corners = {
            -311230.24633853347, 128274.40199699058,
            965659.3148940642, -19071.860591960198,
            592275.8575606591, -284194.93145419937,
            120724.27743743375, -468796.78655199753
        };
        // Cell (Y=0,Z=0): [x-slice][z-boundary][y-boundary].
        slice0[0][0][0] = corners[0];
        slice1[0][0][0] = corners[1];
        slice0[0][0][1] = corners[2];
        slice1[0][0][1] = corners[3];
        slice0[0][1][0] = corners[4];
        slice1[0][1][0] = corners[5];
        slice0[0][1][1] = corners[6];
        slice1[0][1][1] = corners[7];

        int[] input = VanillaTerrainInterpolation.input(4, 8, 1, slice0, slice1);
        int[] output = ExactCompute.reference(input);
        double actualBlock = VanillaTerrainInterpolation.value(output, 4, 8, 1, 0, 0, 0, 1, 1, 1, false);
        double actualFill = VanillaTerrainInterpolation.value(output, 4, 8, 1, 0, 0, 0, 1, 1, 1, true);

        double expectedBlock = Mth.lerp(
            0.25,
            Mth.lerp(0.25, Mth.lerp(0.125, corners[0], corners[2]), Mth.lerp(0.125, corners[1], corners[3])),
            Mth.lerp(0.25, Mth.lerp(0.125, corners[4], corners[6]), Mth.lerp(0.125, corners[5], corners[7]))
        );
        double expectedFill = Mth.lerp3(
            0.25, 0.125, 0.25,
            corners[0], corners[1], corners[2], corners[3],
            corners[4], corners[5], corners[6], corners[7]
        );
        assertBits(expectedBlock, actualBlock);
        assertBits(expectedFill, actualFill);
        assertNotEquals(Double.doubleToRawLongBits(actualBlock), Double.doubleToRawLongBits(actualFill));
    }

    @Test
    void workloadFourRoutesThroughTheExactComputeCodecAndPreservesNegativeCorners() {
        double[][][] slice0 = slice(2, 5, 3);
        double[][][] slice1 = slice(2, 5, 3);
        for (int interpolator = 0; interpolator < 2; interpolator++) {
            for (int z = 0; z < 5; z++) {
                for (int y = 0; y < 3; y++) {
                    double value = (interpolator * 10.0 - z * 2.5) + y * 0.125;
                    slice0[interpolator][z][y] = value;
                    slice1[interpolator][z][y] = -value - 0.25;
                }
            }
        }

        int[] input = VanillaTerrainInterpolation.input(4, 8, 2, slice0, slice1);
        ExactCompute.validate(input);
        assertEquals(4, input[0]);
        assertEquals(input[1] * 2, ExactCompute.outputWords(input));
        int[] output = ExactCompute.reference(input);
        assertEquals(ExactCompute.outputWords(input), output.length);
        assertBits(
            Mth.lerp3(
                0.5, 0.125, 0.25,
                slice0[1][2][1], slice1[1][2][1], slice0[1][2][2], slice1[1][2][2],
                slice0[1][3][1], slice1[1][3][1], slice0[1][3][2], slice1[1][3][2]
            ),
            VanillaTerrainInterpolation.value(output, 4, 8, 2, 1, 1, 2, 1, 2, 1, true)
        );
    }

    @Test
    void rejectsMalformedShapesBoundsAndNonFiniteCorners() {
        double[][][] good = slice(1, 5, 2);
        double[][][] paired = slice(1, 5, 2);
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(3, 8, 1, good, paired));
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 0, 1, good, paired));
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 8, 0, good, paired));
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 8, 1, new double[0][][], new double[0][][]));
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 8, 1, slice(1, 4, 2), paired));

        double[][][] nonFinite = slice(1, 5, 2);
        nonFinite[0][0][0] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 8, 1, nonFinite, paired));
        double[][][] tooLarge = slice(1, 5, 2);
        tooLarge[0][0][0] = VanillaTerrainInterpolation.MAX_CORNER_MAGNITUDE + 1.0;
        assertThrows(IllegalArgumentException.class, () -> VanillaTerrainInterpolation.input(4, 8, 1, tooLarge, paired));

        int[] malformed = VanillaTerrainInterpolation.input(4, 8, 1, good, paired);
        malformed[1]++;
        assertThrows(IllegalArgumentException.class, () -> ExactCompute.validate(malformed));
    }

    @Test
    void rejectsOversizedOutputBeforeAllocatingIt() {
        double[][][] corners = slice(16, 2, 17);
        assertThrows(IllegalArgumentException.class,
            () -> VanillaTerrainInterpolation.input(16, 32, 16, corners, corners));
    }

    private static double[][][] slice(int interpolators, int zBoundaries, int yBoundaries) {
        double[][][] result = new double[interpolators][zBoundaries][yBoundaries];
        for (double[][] interpolator : result) {
            for (double[] row : interpolator) Arrays.fill(row, -0.375);
        }
        return result;
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
