package org.gpur.generation;

public record GPurChunkTerrainData(
    String worldKey,
    int chunkX,
    int chunkZ,
    int minY,
    int height,
    byte[] materials,
    int[] surfaceHeights
) {
    public static final byte AIR = 0;
    public static final byte SOLID = 1;
    public static final byte WATER = 2;
    public static final byte LAVA = 3;

    public int blockIndex(final int localX, final int yIndex, final int localZ) {
        return yIndex * 256 + (localZ << 4) + localX;
    }

    public byte materialAt(final int localX, final int yIndex, final int localZ) {
        return this.materials[this.blockIndex(localX, yIndex, localZ)];
    }

    public int surfaceHeightAt(final int localX, final int localZ) {
        return this.surfaceHeights[(localZ << 4) + localX];
    }
}
