package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VulkanMemoryPolicyTest {
    @Test
    void bufferCapacityGrowsGeometricallyAndRoundsToAlignment() {
        assertEquals(256L, VulkanMemoryPolicy.growthTarget(0L, 129L, 4096L, 256L));
        assertEquals(512L, VulkanMemoryPolicy.growthTarget(256L, 300L, 4096L, 256L));
        assertEquals(1024L, VulkanMemoryPolicy.growthTarget(256L, 900L, 4096L, 256L));
    }

    @Test
    void bufferGrowthNeverExceedsConfiguredMaximum() {
        assertEquals(1024L, VulkanMemoryPolicy.growthTarget(768L, 1000L, 1024L, 256L));
        assertThrows(IllegalArgumentException.class,
            () -> VulkanMemoryPolicy.growthTarget(0L, 1025L, 1024L, 256L));
    }

    @Test
    void nativeAllocationReservationsCannotExceedAggregateBudget() {
        AtomicLong resident = new AtomicLong();

        assertTrue(VulkanMemoryPolicy.tryReserve(resident, 1024L, 768L));
        assertFalse(VulkanMemoryPolicy.tryReserve(resident, 1024L, 257L));
        assertEquals(768L, resident.get());

        resident.addAndGet(-256L);
        assertTrue(VulkanMemoryPolicy.tryReserve(resident, 1024L, 512L));
        assertEquals(1024L, resident.get());
    }
}
