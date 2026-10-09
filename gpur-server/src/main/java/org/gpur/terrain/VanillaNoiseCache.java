package org.gpur.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.gpur.compute.ComputeService;
import org.gpur.compute.VanillaNoiseBatch;
import org.gpur.compute.WorldgenResult;

/**
 * Exact leaf results for one chunk's corner coordinates. The original density graph and its
 * caches remain in control; an unknown sampler or a different coordinate is an ordinary miss.
 */
public final class VanillaNoiseCache implements AutoCloseable {
    private static final ThreadLocal<VanillaNoiseCache> CURRENT = new ThreadLocal<>();
    private final Map<Key, Integer> indices;
    private final WorldgenResult result;

    private VanillaNoiseCache(Map<Key, Integer> indices, WorldgenResult result) {
        this.indices = indices;
        this.result = result;
    }

    /** NaN means a miss. Admitted profiles and coordinates produce finite noise values. */
    public static double lookup(Object sampler, double x, double y, double z) {
        VanillaNoiseCache cache = CURRENT.get();
        if (cache == null) return Double.NaN;
        int[] words = cache.result.readWords();
        if (words == null) return Double.NaN;
        Integer index = cache.indices.get(new Key(sampler, x, y, z));
        if (index == null) return Double.NaN;
        long bits = Integer.toUnsignedLong(words[index * 2])
            | (Integer.toUnsignedLong(words[index * 2 + 1]) << 32);
        cache.result.recordConsumption(1);
        return Double.longBitsToDouble(bits);
    }

    public Scope enter() {
        VanillaNoiseCache previous = CURRENT.get();
        if (this.result.usable()) CURRENT.set(this);
        else CURRENT.remove();
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final VanillaNoiseCache previous;
        private boolean closed;
        private Scope(VanillaNoiseCache previous) { this.previous = previous; }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            if (this.previous == null) CURRENT.remove(); else CURRENT.set(this.previous);
        }
    }

    public boolean usable() { return this.result.usable(); }

    @Override public void close() { this.result.close(); this.indices.clear(); }

    public static final class Builder {
        private final Map<Key, Integer> indices = new HashMap<>();
        private final List<VanillaNoiseBatch.NoiseSample> samples = new ArrayList<>();
        private boolean sealed;

        public void add(Object sampler, VanillaNoiseBatch.NoiseProfile profile, double x, double y, double z) {
            if (this.sealed) throw new IllegalStateException("Noise requests are already submitted");
            Objects.requireNonNull(sampler, "sampler");
            Key key = new Key(sampler, x, y, z);
            if (this.indices.containsKey(key)) return;
            if (this.samples.size() >= VanillaNoiseBatch.MAX_SAMPLES) throw new IllegalArgumentException("Too many corner noise requests");
            this.indices.put(key, this.samples.size());
            this.samples.add(new VanillaNoiseBatch.NoiseSample(profile, x, y, z, 0.0, 0.0));
        }

        public CompletableFuture<VanillaNoiseCache> prepare(ComputeService service, Executor continuation) {
            if (this.sealed) throw new IllegalStateException("Noise requests are already submitted");
            this.sealed = true;
            if (this.samples.isEmpty()) return CompletableFuture.completedFuture(null);
            int[] input = VanillaNoiseBatch.input(this.samples);
            // Charge the retained map entries, boxed indices, table and keys conservatively.
            long ownerBytes = Math.multiplyExact((long)this.samples.size(), 144L);
            this.samples.clear();
            CompletableFuture<VanillaNoiseCache> prepared = new CompletableFuture<>();
            CompletableFuture<WorldgenResult> numeric = service.prepareWorldgenAsync(input, continuation, ownerBytes);
            numeric.whenComplete((frame, failure) -> {
                if (failure != null) { prepared.completeExceptionally(failure); return; }
                if (frame == null) { this.indices.clear(); prepared.complete(null); return; }
                VanillaNoiseCache cache = new VanillaNoiseCache(this.indices, frame);
                if (!prepared.complete(cache)) cache.close();
            });
            prepared.whenComplete((cache, failure) -> { if (failure != null) numeric.cancel(false); });
            return prepared;
        }
    }

    /** Sampler identity is part of the key; signed zero and every coordinate bit are preserved. */
    private static final class Key {
        private final Object sampler;
        private final long x;
        private final long y;
        private final long z;
        private Key(Object sampler, double x, double y, double z) {
            this.sampler = sampler;
            this.x = Double.doubleToRawLongBits(x);
            this.y = Double.doubleToRawLongBits(y);
            this.z = Double.doubleToRawLongBits(z);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && this.sampler == key.sampler && this.x == key.x && this.y == key.y && this.z == key.z;
        }
        @Override public int hashCode() {
            int hash = System.identityHashCode(this.sampler);
            hash = 31 * hash + Long.hashCode(this.x);
            hash = 31 * hash + Long.hashCode(this.y);
            return 31 * hash + Long.hashCode(this.z);
        }
    }
}
