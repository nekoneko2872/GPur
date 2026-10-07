package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DispatchBackoffTest {
    private static final long BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30);

    @Test
    void expiryBoundaryAdmitsAndMarksTheFirstRetryForSampling() {
        DispatchBackoff backoff = new DispatchBackoff(10_000L);

        assertNull(backoff.admit(9_999L, false));
        DispatchBackoff.Ticket atDeadline = backoff.admit(10_000L, false);

        assertNotNull(atDeadline);
        assertTrue(atDeadline.retryProbe());
        assertTrue(backoff.shouldSample(2L, atDeadline));
    }

    @Test
    void slowRetrySampleRearmsBackoffAndStopsTheGpuBurst() {
        DispatchBackoff backoff = new DispatchBackoff(1_000L);
        long now = 1_000L;
        DispatchBackoff.Ticket retry = backoff.admit(now, false);

        // The measured workload was 522 us on GPU versus 19 us for the wire CPU reference.
        assertTrue(backoff.record(retry, 522_000L, 19_000L, false, now));
        assertEquals(now + BACKOFF_NANOS, backoff.retryAfter());
        assertNull(backoff.admit(now + 1L, false));
    }

    @Test
    void fastRetrySampleClearsExpiredDeadline() {
        DispatchBackoff backoff = new DispatchBackoff(5_000L);
        DispatchBackoff.Ticket retry = backoff.admit(5_000L, false);

        assertFalse(backoff.record(retry, 10_000L, 19_000L, false, 5_100L));
        assertEquals(0L, backoff.retryAfter());
        assertNotNull(backoff.admit(5_101L, false));
    }

    @Test
    void keepsTheRegularFirstAndEvery256thSampleCadence() {
        DispatchBackoff backoff = new DispatchBackoff();
        DispatchBackoff.Ticket normal = backoff.admit(1L, false);

        assertTrue(backoff.shouldSample(1L, normal));
        for (long attempt = 2L; attempt < 256L; attempt++) {
            assertFalse(backoff.shouldSample(attempt, normal), "attempt " + attempt);
        }
        assertTrue(backoff.shouldSample(256L, normal));
    }

    @Test
    void staleFastProbeCannotClearADeadlineRenewedByAnotherContext() {
        DispatchBackoff backoff = new DispatchBackoff(10_000L);
        DispatchBackoff.Ticket slowContext = backoff.admit(10_000L, false);
        DispatchBackoff.Ticket fastContext = backoff.admit(10_000L, false);

        assertTrue(backoff.record(slowContext, 522_000L, 19_000L, false, 20_000L));
        long renewedDeadline = 20_000L + BACKOFF_NANOS;
        assertFalse(backoff.record(fastContext, 10_000L, 19_000L, false, 20_001L));
        assertEquals(renewedDeadline, backoff.retryAfter());
    }

    @Test
    void staleSlowProbeCannotRearmAfterAnotherContextClearedTheDeadline() {
        DispatchBackoff backoff = new DispatchBackoff(10_000L);
        DispatchBackoff.Ticket fastContext = backoff.admit(10_000L, false);
        DispatchBackoff.Ticket slowContext = backoff.admit(10_000L, false);

        assertFalse(backoff.record(fastContext, 10_000L, 19_000L, false, 20_000L));
        assertFalse(backoff.record(slowContext, 522_000L, 19_000L, false, 20_001L));
        assertEquals(0L, backoff.retryAfter());
    }

    @Test
    void forceStillBypassesAndDoesNotChangeTheBackoff() {
        DispatchBackoff backoff = new DispatchBackoff(50_000L);
        DispatchBackoff.Ticket forced = backoff.admit(1L, true);

        assertNotNull(forced);
        assertFalse(forced.retryProbe());
        assertFalse(backoff.record(forced, 522_000L, 19_000L, true, 2L));
        assertEquals(50_000L, backoff.retryAfter());
    }
}
