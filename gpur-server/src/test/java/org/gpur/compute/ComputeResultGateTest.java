package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ComputeResultGateTest {
    @Test
    void resultAcceptedBeforeDisableIsLinearizedBeforeLaterResults() throws Exception {
        ComputeResultGate gate = new ComputeResultGate();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch accepting = new CountDownLatch(1);
        CountDownLatch finishAcceptance = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> gate.accept(1, available::get, () -> {
                accepting.countDown();
                await(finishAcceptance);
                accepted.incrementAndGet();
            }));
            assertTrue(accepting.await(5, TimeUnit.SECONDS));
            CountDownLatch disableStarted = new CountDownLatch(1);
            var disable = executor.submit(() -> {
                disableStarted.countDown();
                gate.disable(() -> available.set(false));
            });
            assertTrue(disableStarted.await(5, TimeUnit.SECONDS));
            finishAcceptance.countDown();

            assertTrue(first.get(5, TimeUnit.SECONDS));
            disable.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, accepted.get());
        assertFalse(gate.accept(1, available::get, accepted::incrementAndGet));
        assertEquals(1, accepted.get());
    }

    @Test
    void inFlightDispatchIsRejectedWhenDisableWinsBeforeAcceptance() throws Exception {
        ComputeResultGate gate = new ComputeResultGate();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CountDownLatch finishDispatch = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var dispatch = executor.submit(() -> {
                dispatchStarted.countDown();
                await(finishDispatch);
                return gate.accept(1, available::get, accepted::incrementAndGet);
            });
            assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS));
            gate.disable(() -> available.set(false));
            finishDispatch.countDown();

            assertFalse(dispatch.get(5, TimeUnit.SECONDS));
        }

        assertEquals(0, accepted.get());
    }

    @Test
    void antiXrayRejectionAndCloseRejectLaterAcceptance() {
        ComputeResultGate gate = new ComputeResultGate();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicInteger accepted = new AtomicInteger();

        assertTrue(gate.rejectAntiXray());
        assertFalse(gate.allows(2));
        assertTrue(gate.allows(1));
        assertFalse(gate.accept(2, available::get, accepted::incrementAndGet));
        assertTrue(gate.accept(1, available::get, accepted::incrementAndGet));
        assertFalse(gate.rejectAntiXray());
        gate.close();
        assertTrue(gate.isClosed());
        assertFalse(gate.allows(1));
        assertFalse(gate.accept(1, available::get, accepted::incrementAndGet));
        assertEquals(1, accepted.get());
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test coordination");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating test", interrupted);
        }
    }
}
