package org.gpur.compute;

import java.util.concurrent.atomic.AtomicLong;

/** Pure allocation sizing and reservation policy shared with the Vulkan allocator. */
final class VulkanMemoryPolicy {
    private VulkanMemoryPolicy() {}

    static long growthTarget(long existingCapacity, long requiredBytes, long maxBytes, long alignment) {
        if (requiredBytes <= 0L || maxBytes <= 0L || alignment <= 0L || requiredBytes > maxBytes) {
            throw new IllegalArgumentException("invalid Vulkan buffer size");
        }
        long growth = existingCapacity <= 0L ? alignment
            : existingCapacity >= maxBytes || existingCapacity > maxBytes / 2L ? maxBytes : existingCapacity * 2L;
        long requested = Math.max(requiredBytes, growth);
        long rounded = requested > Long.MAX_VALUE - (alignment - 1L)
            ? Long.MAX_VALUE : ((requested + alignment - 1L) / alignment) * alignment;
        return Math.min(maxBytes, rounded);
    }

    static boolean tryReserve(AtomicLong residentBytes, long budgetBytes, long requestedBytes) {
        if (budgetBytes < 0L || requestedBytes < 0L) return false;
        for (;;) {
            long current = residentBytes.get();
            if (requestedBytes > budgetBytes - current) return false;
            if (residentBytes.compareAndSet(current, current + requestedBytes)) return true;
        }
    }
}
