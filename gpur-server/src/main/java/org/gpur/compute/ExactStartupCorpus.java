package org.gpur.compute;

import java.util.ArrayList;
import java.util.List;

/** Small, deterministic workload-4 cases for checking an exact device after startup or reload. */
public final class ExactStartupCorpus {
    private static final double[] CORNER_VALUES = {
        0.0,
        -0.0,
        Double.MIN_NORMAL,
        -Double.MIN_NORMAL,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        1.0e-100,
        -1.0e-100,
        1.0e6,
        -1.0e6,
        1.0,
        Math.nextUp(1.0),
        Math.nextDown(1.0),
        0.5,
        -0.5,
        2.0,
        -2.0,
        1.0e6 - 0.125,
        -1.0e6 + 0.125,
        Math.ulp(1.0),
        -Math.ulp(1.0),
        0.125,
        -0.125
    };

    private static final int[][] SHAPES = {
        {1, 1, 1},
        {2, 2, 1},
        {4, 4, 2},
        {8, 8, 1},
        {16, 16, 1},
        {1, 32, 2},
        {4, 16, 3},
        {16, 2, 4}
    };

    private static final List<int[]> CORPUS = createCorpus();

    private ExactStartupCorpus() {}

    /** Returns fresh input arrays so callers cannot mutate the shared corpus. */
    public static List<int[]> workloads() {
        List<int[]> copy = new ArrayList<>(CORPUS.size());
        for (int[] input : CORPUS) copy.add(input.clone());
        return List.copyOf(copy);
    }

    private static List<int[]> createCorpus() {
        List<int[]> corpus = new ArrayList<>(SHAPES.length);
        for (int caseIndex = 0; caseIndex < SHAPES.length; caseIndex++) {
            int cellWidth = SHAPES[caseIndex][0];
            int cellHeight = SHAPES[caseIndex][1];
            int cellCountY = SHAPES[caseIndex][2];
            int zBoundaries = 16 / cellWidth + 1;
            int yBoundaries = cellCountY + 1;
            double[][][] slice0 = new double[1][zBoundaries][yBoundaries];
            double[][][] slice1 = new double[1][zBoundaries][yBoundaries];
            for (int z = 0; z < zBoundaries; z++) {
                for (int y = 0; y < yBoundaries; y++) {
                    int corner = (caseIndex * 7 + z * 5 + y * 11) % CORNER_VALUES.length;
                    slice0[0][z][y] = CORNER_VALUES[corner];
                    // Adjacent high-magnitude cancellation and near-zero ties exercise lerp order.
                    slice1[0][z][y] = CORNER_VALUES[(corner + 9) % CORNER_VALUES.length];
                }
            }
            corpus.add(VanillaTerrainInterpolation.input(cellWidth, cellHeight, cellCountY, slice0, slice1));
        }
        return List.copyOf(corpus);
    }
}
