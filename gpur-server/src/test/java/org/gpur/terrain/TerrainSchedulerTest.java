package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TerrainSchedulerTest {
    private static final long SEED = 0x78abcd4512L;
    private static int[] expected(int x, int z) { return TerrainRules.reference(TerrainRules.input(SEED, -64, 128, 0, x, z)); }

    private static TerrainScheduler.Backend backend(int devices) {
        return new TerrainScheduler.Backend() {
            public int devices() { return devices; }
            public boolean available() { return devices > 0; }
            public int batchSize(int device) { return 8; }
            public int[] compute(int device, int[] input) { return TerrainRules.reference(input); }
        };
    }

    @Test void concurrentDifferentSeedsAndChunksReceiveTheirOwnExactResult() throws Exception {
        try (TerrainScheduler scheduler = new TerrainScheduler(backend(2), true, 16, 8, 2, 5000);
             var callers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<int[]>>();
            for (int i = 0; i < 16; i++) {
                final int index = i;
                futures.add(callers.submit(() -> scheduler.generate(SEED + index % 2, -64, 128, 0, index - 8, -index)));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertArrayEquals(TerrainRules.reference(TerrainRules.input(SEED + i % 2, -64, 128, 0, i - 8, -i)),
                    futures.get(i).get(5, TimeUnit.SECONDS));
            }
            assertEquals(16, scheduler.status().gpuChunks());
            assertEquals(0, scheduler.status().cpuChunks());
        }
    }

    @Test void saturationAndCloseReleaseAllCallersWithTheSameCpuTerrain() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1), release = new CountDownLatch(1);
        TerrainScheduler.Backend blocked = new TerrainScheduler.Backend() {
            public int devices() { return 1; }
            public boolean available() { return true; }
            public int batchSize(int device) { return 1; }
            public int[] compute(int device, int[] input) {
                dispatched.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                return TerrainRules.reference(input);
            }
        };
        try (TerrainScheduler scheduler = new TerrainScheduler(blocked, true, 1, 1, 0, 5000);
             var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> scheduler.generate(SEED, -64, 128, 0, 1, 2));
            assertTrue(dispatched.await(5, TimeUnit.SECONDS));
            var second = callers.submit(() -> scheduler.generate(SEED, -64, 128, 0, 3, 4));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scheduler.status().queued() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, scheduler.status().queued());
            assertArrayEquals(expected(5, 6), scheduler.generate(SEED, -64, 128, 0, 5, 6));
            assertEquals(1L, scheduler.status().fallbacks().get(TerrainScheduler.Fallback.QUEUE_FULL));
            scheduler.close();
            assertArrayEquals(expected(1, 2), first.get(5, TimeUnit.SECONDS));
            assertArrayEquals(expected(3, 4), second.get(5, TimeUnit.SECONDS));
            assertEquals(2L, scheduler.status().fallbacks().get(TerrainScheduler.Fallback.STOPPED));
        } finally { release.countDown(); }
    }

    @Test void absentDevicesAndRejectedResultsNeverChangeTheTerrainRules() {
        try (TerrainScheduler scheduler = new TerrainScheduler(backend(0), true, 1, 1, 0, 100)) {
            assertArrayEquals(expected(-9, 8), scheduler.generate(SEED, -64, 128, 0, -9, 8));
            assertEquals(1L, scheduler.status().fallbacks().get(TerrainScheduler.Fallback.UNAVAILABLE));
        }
        TerrainScheduler.Backend rejected = new TerrainScheduler.Backend() {
            public int devices() { return 1; }
            public boolean available() { return true; }
            public int batchSize(int device) { return 1; }
            public int[] compute(int device, int[] input) { return new int[1]; }
        };
        try (TerrainScheduler scheduler = new TerrainScheduler(rejected, true, 1, 1, 0, 1000)) {
            assertArrayEquals(expected(-9, 8), scheduler.generate(SEED, -64, 128, 0, -9, 8));
            assertEquals(0, scheduler.status().gpuChunks());
        }
    }
}
