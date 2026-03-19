package org.gpur.gpu;

import java.util.List;

public record GPurAntiXrayBatchResult(
    String worldKey,
    int chunkX,
    int chunkZ,
    List<GPurAntiXraySectionResult> sections
) {
}
