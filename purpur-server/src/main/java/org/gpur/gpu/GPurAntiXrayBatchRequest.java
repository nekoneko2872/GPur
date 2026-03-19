package org.gpur.gpu;

import java.util.List;

public record GPurAntiXrayBatchRequest(
    String worldKey,
    int chunkX,
    int chunkZ,
    List<GPurAntiXraySectionRequest> sections
) {
}
