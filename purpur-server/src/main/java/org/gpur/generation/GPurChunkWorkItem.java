package org.gpur.generation;

public record GPurChunkWorkItem(
    GPurTerrainProfile profile,
    int chunkX,
    int chunkZ,
    long enqueuedAtNanos
) {
}
