package org.gpur.reload;

import java.util.concurrent.TimeUnit;

public final class GPurReloadRetentionPolicy {
    private GPurReloadRetentionPolicy() {
    }

    public static long computeExpiryNanos(final long nowNanos, final long hotCacheTicks) {
        return nowNanos + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, hotCacheTicks) * 50L);
    }

    public static boolean isExpired(final long expiresAtNanos, final long nowNanos) {
        return expiresAtNanos < nowNanos;
    }
}