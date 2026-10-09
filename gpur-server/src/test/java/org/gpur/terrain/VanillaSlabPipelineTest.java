package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.gpur.compute.VanillaTerrainSlab;
import org.junit.jupiter.api.Test;

class VanillaSlabPipelineTest {
    @Test void pendingGpuJobReleasesCallerAndNeverConsumesOnGpuCallback() {
        Fixture stages = new Fixture();
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);
        assertFalse(result.isDone());
        assertEquals(List.of("prepare0"), stages.events);
        stages.pending.complete(null);
        assertEquals(List.of("prepare0"), stages.events);
        assertFalse(result.isDone());
        cpu.remove().run();
        assertEquals(2, result.join());
        assertEquals(List.of("prepare0", "consume0", "prepare1", "consume1", "finish", "cleanup"), stages.events);
    }

    @Test void failedNumericJobFallsBackAndCleanupHappensOnce() {
        Fixture stages = new Fixture();
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);
        stages.pending.completeExceptionally(new IllegalStateException("simulated device loss"));
        cpu.remove().run();
        assertEquals(2, result.join());
        assertEquals(1, stages.events.stream().filter("cleanup"::equals).count());
    }

    @Test void cancellationDoesNotMutateFurtherBlocksAndStillReleasesLeases() {
        Fixture stages = new Fixture();
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);
        result.cancel(false);
        stages.pending.complete(null);
        cpu.remove().run();
        assertEquals(List.of("prepare0", "cleanup"), stages.events);
    }

    @Test void rejectedCpuQueueFailsWithoutRunningBlockConsumer() throws Exception {
        Fixture stages = new Fixture();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, command -> { throw new RejectedExecutionException("halted"); });
        stages.pending.complete(null);
        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(List.of("prepare0", "cleanup"), stages.events);
    }

    @Test void cpuConsumerFailureCompletesExceptionallyAfterCleanup() {
        Fixture stages = new Fixture() {
            @Override public void consume(int slab, VanillaTerrainSlab values) { throw new IllegalArgumentException("write failed"); }
        };
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        CompletableFuture<Integer> result = VanillaSlabPipeline.start(stages, cpu::add);
        stages.pending.complete(null);
        cpu.remove().run();
        assertTrue(result.isCompletedExceptionally());
        assertEquals(List.of("prepare0", "cleanup"), stages.events);
    }

    private static class Fixture implements VanillaSlabPipeline.Stages<Integer> {
        final List<String> events = new ArrayList<>();
        final CompletableFuture<VanillaTerrainSlab> pending = new CompletableFuture<>();
        public int slabCount() { return 2; }
        public CompletableFuture<VanillaTerrainSlab> prepare(int slab) {
            this.events.add("prepare" + slab);
            return slab == 0 ? this.pending : CompletableFuture.completedFuture(null);
        }
        public void consume(int slab, VanillaTerrainSlab values) { this.events.add("consume" + slab); }
        public Integer finish() { this.events.add("finish"); return 2; }
        public void cleanup() { this.events.add("cleanup"); }
    }
}
