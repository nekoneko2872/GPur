package org.gpur.generation;

public final class GPurBackendGate {
    private GPurBackendGate() {
    }

    public static boolean canUseGpuBatch(
        final GPurComputeMode mode,
        final boolean available,
        final int utilizationPercent,
        final int usageFallback
    ) {
        return mode == GPurComputeMode.VULKAN
            && available
            && (utilizationPercent < 0 || utilizationPercent < usageFallback);
    }

    public static boolean canUseLowLatencyGpuOffload(
        final GPurComputeMode mode,
        final boolean available,
        final int utilizationPercent,
        final int usageFallback,
        final int terrainBatchesInFlight,
        final int activeNoiseTasks
    ) {
        return canUseGpuBatch(mode, available, utilizationPercent, usageFallback)
            && terrainBatchesInFlight <= 0
            && activeNoiseTasks <= 0
            && (utilizationPercent < 0 || utilizationPercent == 0);
    }

    public static boolean canUsePacketGpuOffload(
        final GPurComputeMode mode,
        final boolean available,
        final int utilizationPercent,
        final int usageFallback,
        final int terrainBatchesInFlight,
        final int busyExecutionContexts,
        final int totalExecutionContexts
    ) {
        return canUseGpuBatch(mode, available, utilizationPercent, usageFallback)
            && terrainBatchesInFlight <= 0
            && (totalExecutionContexts <= 0 || busyExecutionContexts < totalExecutionContexts);
    }
}