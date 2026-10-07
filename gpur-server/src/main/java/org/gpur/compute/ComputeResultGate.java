package org.gpur.compute;

import java.util.function.BooleanSupplier;

/** Serializes result acceptance with compute shutdown and fail-closed state changes. */
final class ComputeResultGate {
    private volatile boolean closed;
    private volatile boolean antiXrayRejected;

    boolean allows(final int workload) {
        return !this.closed && !(workload == 2 && this.antiXrayRejected);
    }

    synchronized boolean accept(final int workload, final BooleanSupplier deviceAvailable, final Runnable onAccepted) {
        if (this.closed || workload == 2 && this.antiXrayRejected || !deviceAvailable.getAsBoolean()) return false;
        onAccepted.run();
        return true;
    }

    synchronized void disable(final Runnable disableDevice) {
        disableDevice.run();
    }

    synchronized boolean rejectAntiXray() {
        boolean firstRejection = !this.antiXrayRejected;
        this.antiXrayRejected = true;
        return firstRejection;
    }

    synchronized void close() {
        this.closed = true;
    }

    boolean isClosed() {
        return this.closed;
    }

    boolean isAntiXrayRejected() {
        return this.antiXrayRejected;
    }
}
