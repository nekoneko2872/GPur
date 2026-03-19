package org.gpur.generation;

public record GPurTerrainProfile(
    String worldKey,
    long seed,
    int minY,
    int height,
    int seaLevel,
    int lavaLevel
) {
}
