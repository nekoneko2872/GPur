package org.gpur.compute;

/** Pure fence/timeout state transitions used by the native batch executor. */
final class VulkanBatchLifecycle {
    enum FenceEvent { NOT_READY, SIGNALED, FAILED }

    record Outcome(boolean timedOut, boolean terminal, boolean reclaimContexts, boolean disableDevice,
                   boolean settleJobsNull, boolean publishResults, int usableContextDelta) {
        private static Outcome none() { return new Outcome(false, false, false, false, false, false, 0); }
    }

    private enum State { SUBMITTED, ABANDONED, SIGNALED, ABANDONED_SIGNALED, FAILED, ABANDONED_FAILED }

    private State state = State.SUBMITTED;

    Outcome onFence(FenceEvent event, boolean deadlineReached, int contextCount) {
        if (contextCount <= 0) throw new IllegalArgumentException("contextCount must be positive");
        if (event == FenceEvent.NOT_READY) {
            if (this.state == State.SUBMITTED && deadlineReached) {
                this.state = State.ABANDONED;
                return new Outcome(true, false, false, false, true, false, -contextCount);
            }
            return Outcome.none();
        }
        if (event == FenceEvent.SIGNALED) {
            if (this.state == State.SUBMITTED) {
                this.state = State.SIGNALED;
                return new Outcome(false, true, true, false, false, true, 0);
            }
            if (this.state == State.ABANDONED) {
                this.state = State.ABANDONED_SIGNALED;
                return new Outcome(false, true, true, false, false, false, contextCount);
            }
            return Outcome.none();
        }
        if (this.state == State.SUBMITTED) {
            this.state = State.FAILED;
            return new Outcome(false, true, false, true, true, false, -contextCount);
        }
        if (this.state == State.ABANDONED) {
            this.state = State.ABANDONED_FAILED;
            return new Outcome(false, true, false, true, false, false, 0);
        }
        return Outcome.none();
    }

    boolean abandoned() {
        return this.state == State.ABANDONED || this.state == State.ABANDONED_SIGNALED
            || this.state == State.ABANDONED_FAILED;
    }

    static boolean queueExpired(long enqueuedAtNanos, long nowNanos, long timeoutNanos) {
        return nowNanos - enqueuedAtNanos >= timeoutNanos;
    }
}
