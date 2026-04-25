package org.gpur.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GPurBackendGateTest {
    @Test
    void gpuBatchRequiresVulkanAndHeadroom() {
        assertTrue(GPurBackendGate.canUseGpuBatch(GPurComputeMode.VULKAN, true, 20, 50));
        assertFalse(GPurBackendGate.canUseGpuBatch(GPurComputeMode.CPU, true, 20, 50));
        assertFalse(GPurBackendGate.canUseGpuBatch(GPurComputeMode.VULKAN, true, 80, 50));
    }

    @Test
    void lowLatencyRequiresIdlePipeline() {
        assertTrue(GPurBackendGate.canUseLowLatencyGpuOffload(GPurComputeMode.VULKAN, true, 0, 50, 0, 0));
        assertFalse(GPurBackendGate.canUseLowLatencyGpuOffload(GPurComputeMode.VULKAN, true, 0, 50, 1, 0));
        assertFalse(GPurBackendGate.canUseLowLatencyGpuOffload(GPurComputeMode.VULKAN, true, 0, 50, 0, 1));
    }

    @Test
    void packetOffloadNeedsExecutionCapacity() {
        assertTrue(GPurBackendGate.canUsePacketGpuOffload(GPurComputeMode.VULKAN, true, 0, 50, 0, 1, 4));
        assertFalse(GPurBackendGate.canUsePacketGpuOffload(GPurComputeMode.VULKAN, true, 0, 50, 0, 4, 4));
        assertFalse(GPurBackendGate.canUsePacketGpuOffload(GPurComputeMode.CPU, true, 0, 50, 0, 1, 4));
    }
}