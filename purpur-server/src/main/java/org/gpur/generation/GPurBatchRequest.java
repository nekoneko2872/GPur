package org.gpur.generation;

import java.util.List;

public record GPurBatchRequest(
    GPurTerrainProfile profile,
    List<GPurChunkWorkItem> chunks,
    int configuredBatchSize
) {
}
