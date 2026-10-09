package org.gpur.terrain;

import java.util.concurrent.Executor;
import org.gpur.compute.ComputeService;

/** Binds only the standard, parallel-capable NOISE task to its existing CPU executor. */
public final class VanillaNoiseContinuation implements AutoCloseable {
    private static final ThreadLocal<VanillaNoiseContinuation> CURRENT = new ThreadLocal<>();
    private final VanillaNoiseContinuation previous;
    private final ComputeService service;
    private final Executor executor;
    private boolean used;
    private boolean closed;

    private VanillaNoiseContinuation(ComputeService service, Executor executor) {
        this.previous = CURRENT.get();
        this.service = service;
        this.executor = executor;
        CURRENT.set(this);
    }

    public static VanillaNoiseContinuation enter(ComputeService service, Executor executor) {
        if (!service.tryAcquireVanillaContinuation()) return null;
        try {
            return new VanillaNoiseContinuation(service, executor);
        } catch (RuntimeException | Error failure) {
            service.releaseVanillaContinuation();
            throw failure;
        }
    }

    public static VanillaNoiseContinuation current() { return CURRENT.get(); }

    /** Recognizes future cancellation without treating a real generation failure as cancellation. */
    public static boolean isCancellation(Throwable failure) {
        for (int depth = 0; depth < 32; ++depth) {
            if (failure instanceof java.util.concurrent.CancellationException) return true;
            if (!(failure instanceof java.util.concurrent.CompletionException)
                && !(failure instanceof java.util.concurrent.ExecutionException)) return false;
            Throwable cause = failure.getCause();
            if (cause == null || cause == failure) return false;
            failure = cause;
        }
        return false;
    }
    public ComputeService service() { return this.service; }
    public Executor executor() { return this.executor; }
    public boolean used() { return this.used; }
    public void markUsed() { this.used = true; }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.previous == null) CURRENT.remove();
        else CURRENT.set(this.previous);
        this.service.releaseVanillaContinuation();
    }
}
