package org.gpur.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.gpur.GPurConfig;
import org.gpur.compute.ComputeService;

/** Bounded, independent device workers. Only immutable coordinates and palette arrays cross threads. */
public final class TerrainScheduler implements AutoCloseable {
    interface Backend {
        int devices();
        boolean available();
        int batchSize(int device);
        int[] compute(int device, int[] input);
    }

    public enum Fallback { DISABLED, QUEUE_FULL, TIMEOUT, UNAVAILABLE, STOPPED, INTERRUPTED }
    public record Status(boolean enabled, boolean stopped, int queued, int pending, long gpuChunks,
                         long cpuChunks, Map<Fallback, Long> fallbacks) {
        public Status { fallbacks = Map.copyOf(fallbacks); }
    }
    private record WorldSpec(long seed, int minY, int height, int seaLevel) {}
    private record Outcome(int[] blocks, Fallback reason) {}
    private record Request(WorldSpec spec, int x, int z, CompletableFuture<Outcome> result) {}

    private final Backend backend;
    private final boolean enabled;
    private final ArrayBlockingQueue<Request> queue;
    private final Set<Request> pending = ConcurrentHashMap.newKeySet();
    private final List<Thread> workers = new ArrayList<>();
    private final Map<Fallback, AtomicLong> fallbacks = new EnumMap<>(Fallback.class);
    private final AtomicLong gpuChunks = new AtomicLong();
    private final AtomicLong cpuChunks = new AtomicLong();
    private final int maxBatch, waitMillis, timeoutMillis;
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    public TerrainScheduler(ComputeService compute) {
        this(new Backend() {
            public int devices() { return compute.terrainDeviceCount(); }
            public boolean available() { return compute.activeDeviceCount() > 0; }
            public int batchSize(int device) { return compute.terrainBatchSize(device); }
            public int[] compute(int device, int[] input) { return compute.tryTerrainCompute(device, input); }
        }, GPurConfig.gpuAccelerationEnabled && GPurConfig.terrainGpuEnabled && GPurConfig.terrainCustomEnabled,
            GPurConfig.terrainQueueCapacity, GPurConfig.terrainMaxBatchChunks,
            GPurConfig.terrainBatchWaitMillis, GPurConfig.terrainRequestTimeoutMillis);
    }

    TerrainScheduler(Backend backend, boolean enabled, int capacity, int maxBatch, int waitMillis, int timeoutMillis) {
        if (capacity < 1 || maxBatch < 1 || maxBatch > TerrainRules.MAX_BATCH_CHUNKS
            || waitMillis < 0 || timeoutMillis < 1) throw new IllegalArgumentException("Invalid terrain scheduler limits");
        this.backend = backend;
        this.enabled = enabled;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxBatch = maxBatch;
        this.waitMillis = waitMillis;
        this.timeoutMillis = timeoutMillis;
        for (Fallback reason : Fallback.values()) this.fallbacks.put(reason, new AtomicLong());
        if (enabled) {
            for (int device = 0; device < backend.devices(); device++) {
                final int index = device;
                Thread worker = new Thread(() -> this.run(index), "GPur-terrain-" + index);
                worker.setDaemon(true);
                this.workers.add(worker);
                worker.start();
            }
        }
    }

    /** Called only from a generator worker. World mutations remain in the caller's callback. */
    public int[] generate(long seed, int minY, int height, int seaLevel, int chunkX, int chunkZ) {
        int[] input = TerrainRules.input(seed, minY, height, seaLevel, chunkX, chunkZ);
        Request request = new Request(new WorldSpec(seed, minY, height, seaLevel), chunkX, chunkZ, new CompletableFuture<>());
        Fallback rejected = null;
        synchronized (this.lifecycle) {
            if (this.closed) rejected = Fallback.STOPPED;
            else if (!this.enabled) rejected = Fallback.DISABLED;
            else if (!this.backend.available()) rejected = Fallback.UNAVAILABLE;
            else {
                this.pending.add(request);
                if (!this.queue.offer(request)) {
                    this.pending.remove(request);
                    rejected = Fallback.QUEUE_FULL;
                }
            }
        }
        if (rejected != null) return this.cpu(input, rejected);
        try {
            Outcome outcome = request.result().get(this.timeoutMillis, TimeUnit.MILLISECONDS);
            if (outcome.blocks() != null) {
                this.gpuChunks.incrementAndGet();
                return outcome.blocks();
            }
            return this.cpu(input, outcome.reason());
        } catch (TimeoutException failure) {
            request.result().cancel(false);
            return this.cpu(input, Fallback.TIMEOUT);
        } catch (InterruptedException failure) {
            request.result().cancel(false);
            Thread.currentThread().interrupt();
            return this.cpu(input, Fallback.INTERRUPTED);
        } catch (ExecutionException failure) {
            return this.cpu(input, Fallback.UNAVAILABLE);
        } finally {
            this.pending.remove(request);
        }
    }

    private int[] cpu(int[] input, Fallback reason) {
        this.cpuChunks.incrementAndGet();
        this.fallbacks.get(reason).incrementAndGet();
        return TerrainRules.reference(input);
    }

    private void run(int device) {
        Request deferred = null;
        try {
            while (!this.closed) {
                Request first = deferred == null ? this.queue.take() : deferred;
                deferred = null;
                if (first.result().isDone()) continue;
                List<Request> batch = new ArrayList<>();
                batch.add(first);
                int limit = Math.max(1, Math.min(this.maxBatch, this.backend.batchSize(device)));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.waitMillis);
                while (batch.size() < limit && !this.closed) {
                    long remaining = deadline - System.nanoTime();
                    Request next = remaining > 0 ? this.queue.poll(remaining, TimeUnit.NANOSECONDS) : this.queue.poll();
                    if (next == null) break;
                    if (next.result().isDone()) continue;
                    if (!first.spec().equals(next.spec())) { deferred = next; break; }
                    batch.add(next);
                }
                if (this.closed) break;
                int[] coordinates = new int[batch.size() * 2];
                for (int i = 0; i < batch.size(); i++) {
                    coordinates[i * 2] = batch.get(i).x();
                    coordinates[i * 2 + 1] = batch.get(i).z();
                }
                WorldSpec spec = first.spec();
                int[] input = TerrainRules.input(spec.seed(), spec.minY(), spec.height(), spec.seaLevel(), coordinates);
                int[] result;
                try { result = this.backend.compute(device, input); }
                catch (RuntimeException failure) { result = null; }
                int words = spec.height() * 256;
                if (result != null && result.length != words * batch.size()) result = null;
                for (int i = 0; i < batch.size(); i++) {
                    Request request = batch.get(i);
                    if (request.result().isDone()) continue;
                    request.result().complete(this.closed ? new Outcome(null, Fallback.STOPPED)
                        : result == null ? new Outcome(null, Fallback.UNAVAILABLE)
                        : new Outcome(Arrays.copyOfRange(result, i * words, (i + 1) * words), null));
                }
            }
        } catch (InterruptedException expected) {
            Thread.currentThread().interrupt();
        } finally {
            // Closing drains all outstanding futures, including batches removed from the queue.
            // An unexpected worker exit must also release its deferred request.
            if (deferred != null) deferred.result().complete(new Outcome(null, Fallback.UNAVAILABLE));
        }
    }

    public Status status() {
        Map<Fallback, Long> reasons = new EnumMap<>(Fallback.class);
        this.fallbacks.forEach((reason, count) -> reasons.put(reason, count.get()));
        return new Status(this.enabled, this.closed, this.queue.size(), this.pending.size(),
            this.gpuChunks.get(), this.cpuChunks.get(), reasons);
    }

    @Override public void close() {
        synchronized (this.lifecycle) {
            if (this.closed) return;
            this.closed = true;
            for (Request request : this.pending) request.result().complete(new Outcome(null, Fallback.STOPPED));
            this.queue.clear();
        }
        for (Thread worker : this.workers) worker.interrupt();
    }
}
