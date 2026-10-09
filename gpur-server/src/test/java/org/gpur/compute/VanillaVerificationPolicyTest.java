package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VanillaVerificationPolicyTest {
    @Test
    void parsesOnlyTheSupportedModeSpellings() {
        assertEquals(VanillaVerificationPolicy.Mode.DISABLED, VanillaVerificationPolicy.Mode.parse("disabled"));
        assertEquals(VanillaVerificationPolicy.Mode.OBSERVE, VanillaVerificationPolicy.Mode.parse("observe"));
        assertEquals(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, VanillaVerificationPolicy.Mode.parse("verified-exact"));
        assertEquals(VanillaVerificationPolicy.Mode.STRICT, VanillaVerificationPolicy.Mode.parse("strict"));
        assertThrows(IllegalArgumentException.class, () -> VanillaVerificationPolicy.Mode.parse("EXACT"));
        assertThrows(IllegalArgumentException.class, () -> VanillaVerificationPolicy.Mode.parse("unknown"));
        assertThrows(IllegalArgumentException.class, () -> VanillaVerificationPolicy.Mode.parse(null));
    }

    @Test
    void disabledObserveAndStrictModesHaveSafeVerificationAndAdoptionRules() {
        VanillaVerificationPolicy disabled = policy(VanillaVerificationPolicy.Mode.DISABLED, 0, 1, bound -> 0);
        VanillaVerificationPolicy observe = policy(VanillaVerificationPolicy.Mode.OBSERVE, 0, 1, bound -> 0);
        VanillaVerificationPolicy strict = policy(VanillaVerificationPolicy.Mode.STRICT, 0, 1, bound -> 0);

        assertFalse(disabled.nextVerificationRequired());
        assertFalse(disabled.adoptsResults());
        assertTrue(observe.nextVerificationRequired());
        assertFalse(observe.adoptsResults());
        assertTrue(strict.nextVerificationRequired());
        assertTrue(strict.adoptsResults());
    }

    @Test
    void verifiedExactChecksFirstNThenUsesInjectedOneInNSelector() {
        AtomicInteger requestedBound = new AtomicInteger();
        VanillaVerificationPolicy selected = policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, 2, 4, bound -> {
            requestedBound.set(bound);
            return 0;
        });
        assertTrue(selected.nextVerificationRequired());
        selected.recordVerificationSuccess();
        assertTrue(selected.nextVerificationRequired());
        selected.recordVerificationSuccess();
        assertTrue(selected.nextVerificationRequired());
        assertEquals(4, requestedBound.get());
        selected.recordVerificationSuccess();
        assertTrue(selected.nextVerificationRequired());
        assertTrue(selected.adoptsResults());

        VanillaVerificationPolicy skipped = policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, 0, 4, bound -> bound - 1);
        assertFalse(skipped.nextVerificationRequired());
    }

    @Test
    void warmupStaysFullyVerifiedWhileConcurrentComparisonsAreStillPending() throws Exception {
        VanillaVerificationPolicy policy = policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, 2, 8, bound -> bound - 1);
        CountDownLatch firstComparisonPending = new CountDownLatch(1);
        CountDownLatch finishFirstComparison = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                boolean required = policy.nextVerificationRequired();
                firstComparisonPending.countDown();
                await(finishFirstComparison);
                if (required) policy.recordVerificationSuccess();
                return required;
            });
            try {
                assertTrue(firstComparisonPending.await(5, TimeUnit.SECONDS));
                assertTrue(policy.nextVerificationRequired());
                policy.recordVerificationSuccess();
                // One validation has completed, but the first one is still in flight.
                assertTrue(policy.nextVerificationRequired());
                policy.recordVerificationSuccess();
            } finally {
                finishFirstComparison.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertFalse(policy.nextVerificationRequired());
        }
    }

    @Test
    void rejectsInvalidRatesAndInvalidInjectedRandomResults() {
        assertThrows(IllegalArgumentException.class, () -> policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, -1, 1, bound -> 0));
        assertThrows(IllegalArgumentException.class, () -> policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, 0, 0, bound -> 0));
        VanillaVerificationPolicy invalidSelector = policy(VanillaVerificationPolicy.Mode.VERIFIED_EXACT, 0, 2, bound -> bound);
        assertThrows(IllegalStateException.class, invalidSelector::nextVerificationRequired);
    }

    private static VanillaVerificationPolicy policy(
        VanillaVerificationPolicy.Mode mode,
        int firstFull,
        int sampleOneIn,
        java.util.function.IntUnaryOperator random
    ) {
        return new VanillaVerificationPolicy(mode, firstFull, sampleOneIn, random);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for verification");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating verification", interrupted);
        }
    }
}
