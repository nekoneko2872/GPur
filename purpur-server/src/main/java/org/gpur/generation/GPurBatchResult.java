package org.gpur.generation;

import java.util.List;

public record GPurBatchResult(
    GPurComputeMode mode,
    String deviceName,
    int chunkCount,
    int utilizationPercent,
    long durationNanos,
    List<GPurChunkTerrainData> chunks
) {
}
