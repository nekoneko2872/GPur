package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VulkanAsyncLifecycleTest {
    @Test
    void timeoutSettlesOnceAndLateFenceSignalReclaimsEveryContext() {
        VulkanBatchLifecycle lifecycle = new VulkanBatchLifecycle();

        VulkanBatchLifecycle.Outcome timeout = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.NOT_READY, true, 3);
        assertTrue(timeout.timedOut());
        assertFalse(timeout.terminal());
        assertFalse(timeout.reclaimContexts());
        assertTrue(timeout.settleJobsNull());
        assertEquals(-3, timeout.usableContextDelta());
        assertTrue(lifecycle.abandoned());

        VulkanBatchLifecycle.Outcome stillPending = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.NOT_READY, true, 3);
        assertFalse(stillPending.timedOut());
        assertFalse(stillPending.settleJobsNull());

        VulkanBatchLifecycle.Outcome lateSignal = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.SIGNALED, false, 3);
        assertTrue(lateSignal.terminal());
        assertTrue(lateSignal.reclaimContexts());
        assertFalse(lateSignal.publishResults());
        assertFalse(lateSignal.settleJobsNull());
        assertEquals(3, lateSignal.usableContextDelta());
        assertTrue(lifecycle.abandoned());

        VulkanBatchLifecycle.Outcome duplicateSignal = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.SIGNALED, false, 3);
        assertFalse(duplicateSignal.terminal());
        assertFalse(duplicateSignal.reclaimContexts());
    }

    @Test
    void failedFenceAfterTimeoutDoesNotSettleJobsTwiceOrRecycleContexts() {
        VulkanBatchLifecycle lifecycle = new VulkanBatchLifecycle();
        lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.NOT_READY, true, 2);

        VulkanBatchLifecycle.Outcome failure = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.FAILED, false, 2);

        assertTrue(failure.terminal());
        assertTrue(failure.disableDevice());
        assertFalse(failure.settleJobsNull());
        assertFalse(failure.reclaimContexts());
        assertEquals(0, failure.usableContextDelta());
    }

    @Test
    void ordinarySignalPublishesOnlyAfterWholeBatchFenceCompletes() {
        VulkanBatchLifecycle lifecycle = new VulkanBatchLifecycle();

        VulkanBatchLifecycle.Outcome notReady = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.NOT_READY, false, 4);
        assertFalse(notReady.reclaimContexts());
        assertFalse(notReady.publishResults());

        VulkanBatchLifecycle.Outcome signal = lifecycle.onFence(VulkanBatchLifecycle.FenceEvent.SIGNALED, false, 4);
        assertTrue(signal.reclaimContexts());
        assertTrue(signal.publishResults());
        assertFalse(signal.settleJobsNull());
        assertEquals(0, signal.usableContextDelta());
    }

    @Test
    void queuedJobsExpireAtTheirEnqueueDeadline() {
        assertFalse(VulkanBatchLifecycle.queueExpired(100L, 199L, 100L));
        assertTrue(VulkanBatchLifecycle.queueExpired(100L, 200L, 100L));
    }

    @Test
    void nativeResourcesCannotBeDestroyedUntilDeviceIdleIsConfirmed() {
        VulkanCloseLifecycle lifecycle = new VulkanCloseLifecycle();
        lifecycle.requestClose();

        assertFalse(lifecycle.mayDestroyNativeResources());
        assertThrows(IllegalStateException.class, lifecycle::markDestroyed);

        lifecycle.confirmDeviceIdle();
        assertTrue(lifecycle.mayDestroyNativeResources());
        lifecycle.markDestroyed();
        assertFalse(lifecycle.mayDestroyNativeResources());
    }
}
