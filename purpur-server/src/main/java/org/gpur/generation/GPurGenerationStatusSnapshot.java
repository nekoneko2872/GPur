package org.gpur.generation;

public record GPurGenerationStatusSnapshot(
    GPurComputeMode mode,
    String deviceName,
    boolean available,
    String backendDetail,
    boolean turboModeEnabled,
    boolean terrainAssistEnabled,
    boolean terrainAssistGateOpen,
    int activeNoiseTasks,
    int terrainAssistActiveNoiseThreshold,
    int busyExecutionContexts,
    int totalExecutionContexts,
    int busyPacketExecutionContexts,
    int reservedPacketExecutionContexts,
    int terrainAssistInFlight,
    int terrainAssistInFlightLimit,
    int terrainAssistPreloadPressure,
    long completedTerrainAssistDispatches,
    long completedTerrainAssistColumns,
    long averageTerrainAssistMicrosPerColumn,
    int completedAntiXraySections,
    int completedStructureScans,
    int completedMobScanCandidates
) {
}
