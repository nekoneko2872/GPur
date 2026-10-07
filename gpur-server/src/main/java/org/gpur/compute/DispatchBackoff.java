package org.gpur.compute;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Per-device, per-workload adaptive dispatch backoff. */
final class DispatchBackoff {
    private static final long BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final AtomicLong retryAfter = new AtomicLong();

    DispatchBackoff() {}

    DispatchBackoff(final long initialDeadline) {
        this.retryAfter.set(initialDeadline);
    }

    Ticket admit(final long now, final boolean force) {
        final long observedDeadline = this.retryAfter.get();
        final boolean retryProbe = observedDeadline != 0L && now - observedDeadline >= 0L;
        if (!force && observedDeadline != 0L && !retryProbe) return null;
        return new Ticket(observedDeadline, retryProbe);
    }

    boolean shouldSample(final long attempt, final Ticket ticket) {
        return attempt == 1L || attempt % 256L == 0L || ticket.retryProbe();
    }

    /** Updates only the deadline observed at admission, so a stale probe cannot clear a newer one. */
    boolean record(final Ticket ticket, final long gpuNanos, final long cpuNanos,
                   final boolean force, final long now) {
        if (force) return false;
        final boolean slow = gpuNanos > cpuNanos * 1.1;
        final long nextDeadline = slow ? now + BACKOFF_NANOS : 0L;
        return this.retryAfter.compareAndSet(ticket.observedDeadline(), nextDeadline) && slow;
    }

    long retryAfter() {
        return this.retryAfter.get();
    }

    record Ticket(long observedDeadline, boolean retryProbe) {}
}
