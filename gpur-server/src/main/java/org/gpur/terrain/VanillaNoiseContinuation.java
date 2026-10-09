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

    private VanillaNoiseContinuation(ComputeService service, Executor executor) {
        this.previous = CURRENT.get();
        this.service = service;
        this.executor = executor;
        CURRENT.set(this);
    }

    public static VanillaNoiseContinuation enter(ComputeService service, Executor executor) {
        return new VanillaNoiseContinuation(service, executor);
    }

    public static VanillaNoiseContinuation current() { return CURRENT.get(); }
    public ComputeService service() { return this.service; }
    public Executor executor() { return this.executor; }
    public boolean used() { return this.used; }
    public void markUsed() { this.used = true; }

    @Override public void close() {
        if (this.previous == null) CURRENT.remove();
        else CURRENT.set(this.previous);
    }
}
