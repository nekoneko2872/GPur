package org.gpur.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class GPurReloadRetentionPolicyTest {
    @Test
    void computeExpiryUsesFiftyMillisPerTick() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(600L), GPurReloadRetentionPolicy.computeExpiryNanos(0L, 12L));
    }

    @Test
    void computeExpiryClampsToOneTickMinimum() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(50L), GPurReloadRetentionPolicy.computeExpiryNanos(0L, 0L));
    }

    @Test
    void expiredUsesStrictComparison() {
        assertFalse(GPurReloadRetentionPolicy.isExpired(100L, 100L));
        assertTrue(GPurReloadRetentionPolicy.isExpired(99L, 100L));
    }
}