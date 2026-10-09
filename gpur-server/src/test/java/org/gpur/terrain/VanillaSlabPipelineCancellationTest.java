package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.gpur.compute.ComputeService;
import org.gpur.compute.VanillaTerrainSlab;
import org.junit.jupiter.api.Test;

class VanillaSlabPipelineCancellationTest {
    @Test
    void cancellationWhileGpuFutureIsPendingCleansUpBeforeLateResultArrives() throws Exception {
        NonCancellableFuture pending = new NonCancellableFuture();
        Fixture stages = new Fixture(false, pending);
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);

        assertTrue(result.cancel(false));
        assertTrue(stages.cleanupFinished.get(), "owner cancellation does not wait for the GPU callback");
        assertTrue(pending.cancelRequested.get(), "the pending numeric future should be cancelled when possible");

        VanillaTerrainSlab lateSlab = slab(stages);
        assertTrue(pending.complete(lateSlab), "the simulated native completion may race with cancellation");
        assertEquals(0, cpu.size(), "cancelled late results must be discarded before executor submission");
        assertFalse(lateSlab.usable(), "a late result is closed without waiting for the CPU queue");
        assertEquals(1, stages.slabReleases.get());
        assertEquals(0, stages.consumeCalls.get());
        assertEquals(1, stages.cleanupCalls.get());
    }

    @Test
    void cancellationClosesAcceptedSlabAndCleansUpEvenWhenQueuedCpuWorkIsDiscarded() throws Exception {
        Fixture stages = new Fixture(false);
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);
        VanillaTerrainSlab slab = slab(stages);

        assertTrue(stages.pending.complete(slab));
        assertEquals(1, cpu.size(), "the accepted CPU continuation is waiting in the executor queue");
        assertTrue(slab.usable());

        assertTrue(result.cancel(false));
        assertTrue(stages.cleanupFinished.get(), "cancellation releases stage-owned references immediately");
        assertFalse(slab.usable(), "cancellation closes a transferred numeric lease immediately");
        assertEquals(1, stages.slabReleases.get());
        assertEquals(List.of("prepare0", "cleanup"), stages.events);

        cpu.clear(); // Simulate shutdown discarding the accepted continuation.
        assertEquals(0, stages.consumeCalls.get());
        assertEquals(1, stages.cleanupCalls.get());
    }

    @Test
    void serviceCloseCancelsTrackedPipelineAfterExecutorAcceptedButDiscardedItsContinuation() {
        boolean priorGpuAcceleration = GPurConfig.gpuAccelerationEnabled;
        GPurConfig.gpuAccelerationEnabled = false;
        try (ComputeService service = new ComputeService(Logger.getLogger("pipeline-lifecycle"))) {
            Fixture stages = new Fixture(false);
            ArrayDeque<Runnable> cpu = new ArrayDeque<>();
            CompletableFuture<Integer> pipeline = VanillaSlabPipeline.start(stages, cpu::add);
            CompletableFuture<Integer> tracked = service.trackVanillaGeneration(pipeline);

            assertSame(pipeline, tracked);
            assertTrue(stages.pending.complete(null));
            assertEquals(1, cpu.size(), "the executor accepted a CPU continuation which this test will discard");
            assertEquals(0, stages.consumeCalls.get());

            service.close();

            assertTrue(tracked.isCancelled(), "service shutdown cancels the enclosing generation future");
            assertTrue(stages.cleanupFinished.get(), "cancellation cleanup runs without waiting for the executor queue");
            assertEquals(1, stages.cleanupCalls.get());
            cpu.clear(); // Simulate an executor accepting the continuation and then discarding it.
            assertEquals(0, stages.consumeCalls.get(), "discarded work cannot mutate the chunk after shutdown");
            assertEquals(List.of("prepare0", "cleanup"), stages.events);
        } finally {
            GPurConfig.gpuAccelerationEnabled = priorGpuAcceleration;
        }
    }

    @Test
    void cancellationWaitsForTheSerialCpuOwnerBeforeCleanupClosesItsLease() throws Exception {
        Fixture stages = new Fixture(true);
        try (ExecutorService cpu = Executors.newSingleThreadExecutor();
             ExecutorService canceller = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu);
            VanillaTerrainSlab slab = slab(stages);
            assertTrue(stages.pending.complete(slab));
            assertTrue(stages.consumeStarted.await(5, TimeUnit.SECONDS), "CPU continuation did not start");

            CountDownLatch cancelStarted = new CountDownLatch(1);
            Future<Boolean> cancellation = canceller.submit(() -> {
                cancelStarted.countDown();
                return result.cancel(false);
            });
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS));
            awaitCancelled(result);
            assertFalse(stages.cleanupFinished.get(), "cleanup must not run concurrently with CPU mutation");
            assertTrue(slab.usable(), "the slab lease stays live while its consumer owns it");

            stages.allowConsume.countDown();
            assertTrue(cancellation.get(5, TimeUnit.SECONDS), "the owner result should be cancelled");
            assertTrue(stages.cleanupFinishedLatch.await(5, TimeUnit.SECONDS), "cancellation cleanup did not finish");
            assertTrue(result.isCancelled());
            assertEquals(0, stages.cleanupOverlappedConsume.get());
            assertEquals(1, stages.consumeCalls.get());
            assertEquals(1, stages.cleanupCalls.get());
            assertEquals(1, stages.slabReleases.get());
            assertFalse(slab.usable());
            assertEquals(List.of("prepare0", "consume0-start", "consume0-end", "cleanup"), stages.events);
        } finally {
            stages.allowConsume.countDown();
        }
    }

    private static VanillaTerrainSlab slab(Fixture stages) throws Exception {
        Constructor<VanillaTerrainSlab> constructor = VanillaTerrainSlab.class.getDeclaredConstructor(
            int[].class, int.class, int.class, int.class, java.util.function.BooleanSupplier.class, Runnable.class
        );
        constructor.setAccessible(true);
        java.util.function.BooleanSupplier usable = () -> true;
        Runnable release = stages.slabReleases::incrementAndGet;
        return constructor.newInstance(new int[1], 1, 1, 1, usable, release);
    }

    private static void awaitCancelled(CompletableFuture<?> result) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isCancelled() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(result.isCancelled(), "cancellation request did not reach the owner future");
    }

    private static final class Fixture implements VanillaSlabPipeline.Stages<Integer> {
        private final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CompletableFuture<VanillaTerrainSlab> pending;
        private final CountDownLatch consumeStarted = new CountDownLatch(1);
        private final CountDownLatch allowConsume = new CountDownLatch(1);
        private final CountDownLatch cleanupFinishedLatch = new CountDownLatch(1);
        private final AtomicBoolean cleanupFinished = new AtomicBoolean();
        private final AtomicBoolean consuming = new AtomicBoolean();
        private final AtomicInteger cleanupOverlappedConsume = new AtomicInteger();
        private final AtomicInteger consumeCalls = new AtomicInteger();
        private final AtomicInteger cleanupCalls = new AtomicInteger();
        private final AtomicInteger slabReleases = new AtomicInteger();
        private final boolean blockConsume;

        private Fixture(boolean blockConsume) { this(blockConsume, new CompletableFuture<>()); }
        private Fixture(boolean blockConsume, CompletableFuture<VanillaTerrainSlab> pending) {
            this.blockConsume = blockConsume;
            this.pending = pending;
        }
        @Override public int slabCount() { return 1; }
        @Override public CompletableFuture<VanillaTerrainSlab> prepare(int slab) {
            this.events.add("prepare" + slab);
            return this.pending;
        }
        @Override public void consume(int slab, VanillaTerrainSlab values) {
            this.consumeCalls.incrementAndGet();
            if (this.blockConsume) {
                this.consuming.set(true);
                this.events.add("consume" + slab + "-start");
                this.consumeStarted.countDown();
                try {
                    if (!this.allowConsume.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out awaiting consume release");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("CPU consume was interrupted", interrupted);
                } finally {
                    this.consuming.set(false);
                }
                this.events.add("consume" + slab + "-end");
            }
        }
        @Override public Integer finish() { this.events.add("finish"); return 1; }
        @Override public void cleanup() {
            if (this.consuming.get()) this.cleanupOverlappedConsume.incrementAndGet();
            this.events.add("cleanup");
            this.cleanupCalls.incrementAndGet();
            this.cleanupFinished.set(true);
            this.cleanupFinishedLatch.countDown();
        }
    }

    private static final class NonCancellableFuture extends CompletableFuture<VanillaTerrainSlab> {
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelRequested.set(true);
            return false;
        }
    }
}
