package org.gpur.compute;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/**
 * Per-device verification schedule for exact vanilla compute results.
 * Create a new instance when the device or its driver is reloaded.
 */
public final class VanillaVerificationPolicy {
    public static final int DEFAULT_INITIAL_FULL_VERIFICATIONS = 16;
    public static final int DEFAULT_SAMPLE_ONE_IN = 128;

    public enum Mode {
        DISABLED,
        OBSERVE,
        VERIFIED_EXACT,
        STRICT;

        /** Parses the exact configuration spellings for a verification mode. */
        public static Mode parse(String value) {
            if (value == null) throw new IllegalArgumentException("Vanilla verification mode is required");
            return switch (value) {
                case "disabled" -> DISABLED;
                case "observe" -> OBSERVE;
                case "verified-exact" -> VERIFIED_EXACT;
                case "strict" -> STRICT;
                default -> throw new IllegalArgumentException("Unknown vanilla verification mode: " + value);
            };
        }
    }

    private final Mode mode;
    private final int initialFullVerifications;
    private final int sampleOneIn;
    private final IntUnaryOperator boundedRandom;
    private final Object randomLock = new Object();
    private final AtomicLong successfulValidations = new AtomicLong();

    /** Uses the default warmup and sampling rates. */
    public VanillaVerificationPolicy(Mode mode) {
        this(mode, DEFAULT_INITIAL_FULL_VERIFICATIONS, DEFAULT_SAMPLE_ONE_IN);
    }

    /** Uses a thread-local random selector for the post-warmup sample. */
    public VanillaVerificationPolicy(Mode mode, int initialFullVerifications, int sampleOneIn) {
        this(mode, initialFullVerifications, sampleOneIn, bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /**
     * Creates a policy with an injectable bounded random selector, useful for deterministic tests.
     * The selector must return an integer in {@code [0, bound)}.
     */
    public VanillaVerificationPolicy(
        Mode mode,
        int initialFullVerifications,
        int sampleOneIn,
        IntUnaryOperator boundedRandom
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (initialFullVerifications < 0) throw new IllegalArgumentException("initialFullVerifications must be non-negative");
        if (sampleOneIn < 1) throw new IllegalArgumentException("sampleOneIn must be positive");
        this.initialFullVerifications = initialFullVerifications;
        this.sampleOneIn = sampleOneIn;
        this.boundedRandom = Objects.requireNonNull(boundedRandom, "boundedRandom");
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * Decides whether the next dispatched batch must be compared with the CPU reference.
     * Batches remain fully checked until the configured number of successful comparisons has
     * completed; later batches are checked with probability 1/sampleOneIn. Each call represents
     * one dispatched batch.
     */
    public boolean nextVerificationRequired() {
        return switch (this.mode) {
            case DISABLED -> false;
            case OBSERVE, STRICT -> true;
            case VERIFIED_EXACT -> this.nextVerifiedExactCheck();
        };
    }

    /** Whether this mode permits a result to be adopted after required verification succeeds. */
    public boolean adoptsResults() {
        return this.mode == Mode.VERIFIED_EXACT || this.mode == Mode.STRICT;
    }

    /** Records one full CPU comparison that matched the device result. */
    public void recordVerificationSuccess() {
        this.successfulValidations.incrementAndGet();
    }

    private boolean nextVerifiedExactCheck() {
        if (this.successfulValidations.get() < this.initialFullVerifications) return true;
        final int selected;
        synchronized (this.randomLock) {
            selected = this.boundedRandom.applyAsInt(this.sampleOneIn);
        }
        if (selected < 0 || selected >= this.sampleOneIn) {
            throw new IllegalStateException("Bounded random selector returned a value outside [0, bound)");
        }
        return selected == 0;
    }
}
