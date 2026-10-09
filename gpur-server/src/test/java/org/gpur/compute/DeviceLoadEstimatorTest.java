package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DeviceLoadEstimatorTest {
    @Test void reservesPendingWorkAndLearnsDifferentDeviceLatencies() {
        DeviceLoadEstimator fast = new DeviceLoadEstimator();
        DeviceLoadEstimator slow = new DeviceLoadEstimator();
        fast.reserve(1000);
        assertTrue(fast.predictedNanos(100) > slow.predictedNanos(100));
        fast.complete(1000, 1000, true);
        slow.reserve(1000);
        slow.complete(1000, 1000000, true);
        assertTrue(fast.predictedNanos(100) < slow.predictedNanos(100));
    }

    @Test void failedJobReleasesItsReservationWithoutBiasingThroughput() {
        DeviceLoadEstimator device = new DeviceLoadEstimator();
        double idle = device.predictedNanos(100);
        device.reserve(500);
        device.complete(500, 500000000, false);
        assertEquals(idle, device.predictedNanos(100));
    }
}
