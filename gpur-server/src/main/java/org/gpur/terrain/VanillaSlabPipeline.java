package org.gpur.terrain;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.gpur.compute.VanillaTerrainSlab;

/** Serial CPU ownership across asynchronous numeric jobs. No world state is sent to a device. */
public final class VanillaSlabPipeline<R> {
    public interface Stages<R> {
        int slabCount();
        CompletableFuture<VanillaTerrainSlab> prepare(int slab);
        void consume(int slab, VanillaTerrainSlab values);
        R finish();
        void cleanup();
    }

    private final Stages<R> stages;
    private final Executor executor;
    private final CompletableFuture<R> result = new CompletableFuture<>();
    private int next;
    private boolean cleaned;

    private VanillaSlabPipeline(Stages<R> stages, Executor executor) {
        this.stages = stages;
        this.executor = executor;
    }

    public static <R> CompletableFuture<R> start(Stages<R> stages, Executor executor) {
        VanillaSlabPipeline<R> pipeline = new VanillaSlabPipeline<>(stages, executor);
        pipeline.advance();
        return pipeline.result;
    }

    private void advance() {
        try {
            while (!this.result.isCancelled() && this.next < this.stages.slabCount()) {
                CompletableFuture<VanillaTerrainSlab> pending = this.stages.prepare(this.next);
                if (!pending.isDone()) {
                    pending.whenComplete((values, failure) -> this.resume(values, failure));
                    return;
                }
                VanillaTerrainSlab values;
                try { values = pending.join(); }
                catch (CompletionException | java.util.concurrent.CancellationException failure) { values = null; }
                try { this.stages.consume(this.next++, values); }
                finally { if (values != null) values.close(); }
            }
            R finished = this.result.isCancelled() ? null : this.stages.finish();
            this.cleanup();
            if (!this.result.isCancelled()) this.result.complete(finished);
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private void resume(VanillaTerrainSlab values, Throwable failure) {
        try {
            this.executor.execute(() -> {
                try {
                    try {
                        if (!this.result.isCancelled()) this.stages.consume(this.next++, failure == null ? values : null);
                    } finally { if (values != null) values.close(); }
                    this.advance();
                } catch (Throwable cpuFailure) { this.fail(cpuFailure); }
            });
        } catch (Throwable rejected) {
            if (values != null) values.close();
            // Shutdown can halt the world queue. Only cleanup and exceptional completion run here;
            // never execute block mutation or dependent callbacks on the native submission thread.
            try { Thread.ofVirtual().name("GPur-noise-cleanup").start(() -> this.fail(rejected)); }
            catch (Throwable cleanupThreadFailure) {
                rejected.addSuppressed(cleanupThreadFailure);
                this.fail(rejected);
            }
        }
    }

    private void cleanup() {
        if (!this.cleaned) {
            this.cleaned = true;
            this.stages.cleanup();
        }
    }

    private void fail(Throwable failure) {
        try { this.cleanup(); }
        catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
        this.result.completeExceptionally(failure);
    }
}
