package org.gpur.preload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GPurGenerationConcurrencyTest {
    @Test
    void paperAutomaticAndUnlimitedSentinelsArePreserved() {
        assertEquals(88L, GPurGenerationConcurrency.paperLimit(0L, 10));
        assertEquals(Integer.MAX_VALUE, GPurGenerationConcurrency.effectiveLimit(-1L, 10, true, true, 2.0D, 8));
        assertEquals(88L, GPurGenerationConcurrency.effectiveLimit(0L, 10, false, true, 2.0D, 8));
    }

    @Test
    void flightBoostIsOptInAndDoesNotLowerPaperBaseline() {
        assertEquals(4L, GPurGenerationConcurrency.effectiveLimit(4L, 0, false, true, 2.0D, 8));
        assertEquals(4L, GPurGenerationConcurrency.effectiveLimit(4L, 0, true, false, 2.0D, 8));
        assertEquals(6L, GPurGenerationConcurrency.effectiveLimit(4L, 0, true, true, 2.0D, 2));
    }

    @Test
    void flightBoostAddsBoundedConcurrencyAbovePaperLimit() {
        assertEquals(8L, GPurGenerationConcurrency.effectiveLimit(4L, 0, true, true, 2.0D, 8));
        assertEquals(10L, GPurGenerationConcurrency.effectiveLimit(5L, 0, true, true, 2.0D, 8));
        assertEquals(96L, GPurGenerationConcurrency.effectiveLimit(0L, 10, true, true, 2.0D, 8));
        assertEquals(88L, GPurGenerationConcurrency.effectiveLimit(0L, 10, true, true, 2.0D, 0));
    }

    @Test
    void invalidBoundsAndOverflowCannotWrapOrReduceTheBaseline() {
        assertEquals(69L, GPurGenerationConcurrency.applyFlightBoost(5L, Double.MAX_VALUE, 1000));
        assertEquals(5L, GPurGenerationConcurrency.applyFlightBoost(5L, 2.0D, 0));
        assertEquals(5L, GPurGenerationConcurrency.applyFlightBoost(5L, 2.0D, -1));
        assertEquals(5L, GPurGenerationConcurrency.applyFlightBoost(5L, Double.NaN, 64));
        assertEquals(Long.MAX_VALUE, GPurGenerationConcurrency.applyFlightBoost(Long.MAX_VALUE - 1L, 2.0D, 64));
        assertEquals(Long.MAX_VALUE / 5L, GPurGenerationConcurrency.paperLimit(0L, Integer.MAX_VALUE));
    }
}
