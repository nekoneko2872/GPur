package org.gpur.compute;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** A chunk-owned numeric result. Closing it drops its array and returns the retained CPU budget. */
public final class WorldgenResult implements AutoCloseable {
    private volatile int[] words;
    private final BooleanSupplier valid;
    private final Runnable release;
    private final LongConsumer consumed;

    WorldgenResult(int[] words, BooleanSupplier valid, Runnable release, LongConsumer consumed) {
        this.words = Objects.requireNonNull(words);
        this.valid = Objects.requireNonNull(valid);
        this.release = Objects.requireNonNull(release);
        this.consumed = Objects.requireNonNull(consumed);
    }

    public boolean usable() { return this.words != null && this.valid.getAsBoolean(); }
    public int wordCount() { int[] current = this.words; return current == null ? 0 : current.length; }

    public int word(int index) {
        int[] current = this.words;
        if (current == null) throw new IllegalStateException("Worldgen result is closed");
        return current[index];
    }

    /**
     * Returns a read-only array lease for one CPU operation, or null for fallback. Retain this
     * local reference while reading it: concurrent shutdown may close the frame, but can never
     * recycle or mutate the Java array. Callers must not write to it or retain it after their use.
     */
    public int[] readWords() {
        int[] current = this.words;
        return current != null && this.valid.getAsBoolean() ? current : null;
    }

    /** Counts use by the original CPU algorithm, independently from completed GPU dispatches. */
    public void recordConsumption(long values) {
        if (values < 0) throw new IllegalArgumentException("Negative consumed value count");
        if (this.usable()) this.consumed.accept(values);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (this.words == null) return;
            this.words = null;
        }
        this.release.run();
    }
}
