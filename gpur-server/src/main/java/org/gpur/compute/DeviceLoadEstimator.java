package org.gpur.compute;

/** Per-device latency estimate. It is a scheduling hint, never an admission or correctness gate. */
final class DeviceLoadEstimator {
    private long pendingValues;
    private double nanosPerValue = 100.0;

    synchronized double predictedNanos(int values) {
        return ((double)this.pendingValues + values) * this.nanosPerValue;
    }

    synchronized void reserve(int values) {
        this.pendingValues += values;
    }

    synchronized void complete(int values, long elapsedNanos, boolean measured) {
        this.pendingValues = Math.max(0, this.pendingValues - values);
        if (measured && values > 0 && elapsedNanos > 0) {
            double sample = (double)elapsedNanos / values;
            this.nanosPerValue = this.nanosPerValue * 0.875 + sample * 0.125;
        }
    }
}
