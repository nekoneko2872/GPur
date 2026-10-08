package org.gpur.preload;

/** Resolves Paper's per-player generation limit and applies GPur's opt-in flight boost. */
public final class GPurGenerationConcurrency {
    private static final long PAPER_UNLIMITED_LIMIT = Integer.MAX_VALUE;
    private static final int MIN_EXTRA_LIMIT = 0;
    private static final int MAX_EXTRA_LIMIT = 64;

    private GPurGenerationConcurrency() {
    }

    /**
     * Preserves Paper's configured sentinels: zero selects its per-player automatic limit,
     * while any negative value means unlimited. The load-radius expression matches Paper's
     * current player chunk loader formula, with saturating arithmetic for invalid extremes.
     */
    public static long paperLimit(final long configuredLimit, final int loadDistance) {
        if (configuredLimit < 0L) {
            return PAPER_UNLIMITED_LIMIT;
        }
        if (configuredLimit > 0L) {
            return configuredLimit;
        }

        final long nonNegativeDistance = Math.max(0L, loadDistance);
        final long diameter = (2L * nonNegativeDistance) + 1L;
        final long radiusChunks = saturatingMultiply(diameter, diameter);
        return Math.max(5L, radiusChunks / 5L);
    }

    /**
     * Adds a bounded Elytra allowance on top of Paper's resolved baseline. Paper's current
     * automatic or explicit limit is never reduced; maxExtra bounds GPur's added concurrency.
     */
    public static long applyFlightBoost(final long paperLimit, final double multiplier, final int maxExtra) {
        if (paperLimit <= 0L || Double.isNaN(multiplier) || multiplier <= 1.0D) {
            return paperLimit;
        }

        final long cap = Math.max(MIN_EXTRA_LIMIT, Math.min(MAX_EXTRA_LIMIT, (long)maxExtra));
        final double scaledExtra = paperLimit * (multiplier - 1.0D);
        final long desiredExtra = !Double.isFinite(scaledExtra) || scaledExtra >= Long.MAX_VALUE
            ? Long.MAX_VALUE : Math.max(0L, (long)Math.ceil(scaledExtra));
        final long extra = Math.min(desiredExtra, cap);
        return paperLimit > Long.MAX_VALUE - extra ? Long.MAX_VALUE : paperLimit + extra;
    }

    public static long effectiveLimit(
        final long configuredLimit,
        final int loadDistance,
        final boolean flightBoostEnabled,
        final boolean elytraProfileActive,
        final double multiplier,
        final int maxExtra
    ) {
        final long paperLimit = paperLimit(configuredLimit, loadDistance);
        if (!flightBoostEnabled || !elytraProfileActive || configuredLimit < 0L) {
            return paperLimit;
        }
        return applyFlightBoost(paperLimit, multiplier, maxExtra);
    }

    private static long saturatingMultiply(final long left, final long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
