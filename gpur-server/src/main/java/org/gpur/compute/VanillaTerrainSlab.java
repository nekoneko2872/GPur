package org.gpur.compute;

/** Immutable interpolated values for one vanilla X cell slab. No world state crosses devices. */
public final class VanillaTerrainSlab {
    private final int[] values;
    private final int width;
    private final int height;
    private final int cellsY;

    VanillaTerrainSlab(int[] values, int width, int height, int cellsY) {
        this.values = values;
        this.width = width;
        this.height = height;
        this.cellsY = cellsY;
    }

    public double value(int interpolator, int cellY, int cellZ, int y, int x, int z, boolean fillingCell) {
        return VanillaTerrainInterpolation.value(this.values, this.width, this.height, this.cellsY,
            interpolator, cellY, cellZ, y, x, z, fillingCell);
    }
}
