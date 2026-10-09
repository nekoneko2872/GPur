package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncVanillaComputeTest {
    @TempDir Path directory;

    @Test void verifiesOnCpuExecutorAndQuarantinesMismatchedResult() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        CompletableFuture<int[]> raw = new CompletableFuture<>();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(raw);
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        int[] input = input();
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            CompletableFuture<int[]> result = service.tryVanillaComputeAsync(input, cpu::add);
            int[] corrupted = ExactCompute.reference(input);
            corrupted[10] ^= 1;
            raw.complete(corrupted);
            assertFalse(result.isDone());
            assertEquals(0, service.vanillaTerrainStatus().parityFailures());
            cpu.remove().run();
            assertNull(result.join());
            assertEquals(1, service.vanillaTerrainStatus().parityFailures());
            verify(device).disable();
            assertEquals(0, service.vanillaTerrainStatus().devices().getFirst().slabs());
        }
    }

    @Test void observeChecksEveryResultAndNeverAdoptsIt() throws Exception {
        configure("observe");
        VulkanDevice device = backend();
        int[] input = input();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(ExactCompute.reference(input)));
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            assertNull(service.tryVanillaComputeAsync(input, Runnable::run).join());
            var status = service.vanillaTerrainStatus().devices().getFirst();
            assertEquals(1, status.observedSlabs());
            assertEquals(1, status.paritySamples());
            assertEquals(0, status.slabs());
        }
    }

    @Test void reloadCloseDiscardsResultFromPreviousService() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        CompletableFuture<int[]> raw = new CompletableFuture<>();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(raw);
        ArrayDeque<Runnable> cpu = new ArrayDeque<>();
        int[] input = input();
        ComputeService previous = new ComputeService(Logger.getLogger("test"), List.of(device));
        CompletableFuture<int[]> result = previous.tryVanillaComputeAsync(input, cpu::add);
        previous.close();
        raw.complete(ExactCompute.reference(input));
        cpu.remove().run();
        assertNull(result.join());
        assertEquals(0, previous.vanillaTerrainStatus().devices().getFirst().slabs());
    }

    @Test void callerMutationCannotChangePendingSnapshotOrItsCpuReference() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        CompletableFuture<int[]> raw = new CompletableFuture<>();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(raw);
        int[] input = input();
        int[] expected = ExactCompute.reference(input);
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            CompletableFuture<int[]> result = service.tryVanillaComputeAsync(input, Runnable::run);
            input[10] ^= 0x7fffffff;
            raw.complete(expected);
            assertArrayEquals(expected, result.join());
            assertEquals(0, service.vanillaTerrainStatus().parityFailures());
        }
    }

    @Test void backendFailureReturnsOriginalCpuPath() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("device lost")));
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            assertNull(service.tryVanillaComputeAsync(input(), Runnable::run).join());
            assertEquals(1, service.vanillaTerrainStatus().cpuFallbacks());
        }
    }

    @Test void quarantineInvalidatesCollectedSlabBeforeCpuInstallation() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        AtomicBoolean corrupt = new AtomicBoolean();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] result = ExactCompute.reference(invocation.getArgument(0));
            if (corrupt.get()) result[10] ^= 1;
            return CompletableFuture.completedFuture(result);
        });
        double[][][] lower = new double[1][5][3];
        double[][][] upper = new double[1][5][3];
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            VanillaTerrainSlab collected = service.prepareVanillaSlabAsync(4, 8, 2, lower, upper, Runnable::run).join();
            assertNotNull(collected);
            assertTrue(collected.usable());
            corrupt.set(true);
            assertNull(service.tryVanillaComputeAsync(input(), Runnable::run).join());
            assertFalse(collected.usable(), "a collected snapshot must be rejected at installation after quarantine");
            collected.close();
        }
    }

    @Test void pendingCpuSnapshotsRemainBoundedUntilConsumedOrCancelled() throws Exception {
        configure("strict");
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation ->
            CompletableFuture.completedFuture(ExactCompute.reference(invocation.getArgument(0))));
        double[][][] lower = new double[1][5][3];
        double[][][] upper = new double[1][5][3];
        long oneSnapshotBytes = (long)input().length * Integer.BYTES + (long)input()[1] * Double.BYTES;
        // Make the production snapshot budget hold exactly one result without allocating large fixtures.
        java.lang.reflect.Field budget = ComputeService.class.getDeclaredField("vanillaSnapshotByteLimit");
        budget.setAccessible(true);
        try (ComputeService service = new ComputeService(Logger.getLogger("test"), List.of(device))) {
            budget.setLong(service, oneSnapshotBytes);
            VanillaTerrainSlab first = service.prepareVanillaSlabAsync(4, 8, 2, lower, upper, Runnable::run).join();
            assertNotNull(first);
            assertNull(service.prepareVanillaSlabAsync(4, 8, 2, lower, upper, Runnable::run).join());
            first.close();
            first.close(); // Release is idempotent.
            assertFalse(first.usable());
            ArrayDeque<Runnable> cpu = new ArrayDeque<>();
            CompletableFuture<VanillaTerrainSlab> pending = service.prepareVanillaSlabAsync(4, 8, 2, lower, upper, cpu::add);
            assertTrue(pending.cancel(false));
            cpu.remove().run();
            VanillaTerrainSlab afterCancellation = service.prepareVanillaSlabAsync(4, 8, 2, lower, upper, Runnable::run).join();
            assertNotNull(afterCancellation, "cancelled CPU handoff must release its snapshot budget");
            afterCancellation.close();
        }
    }

    private void configure(String mode) throws Exception {
        Path config = this.directory.resolve("gpur.yml");
        Files.writeString(config, "chunk-generation:\n  vanilla-terrain:\n    mode: " + mode + "\n");
        GPurConfig.init(config.toFile());
    }

    private static VulkanDevice backend() {
        VulkanDevice device = mock(VulkanDevice.class);
        AtomicBoolean available = new AtomicBoolean(true);
        when(device.available()).thenAnswer(ignored -> available.get());
        when(device.uuid()).thenReturn("simulated");
        when(device.name()).thenReturn("Simulated GPU");
        doAnswer(ignored -> { available.set(false); return null; }).when(device).disable();
        return device;
    }

    private static int[] input() {
        double[][][] lower = new double[1][5][3];
        double[][][] upper = new double[1][5][3];
        for (int z = 0; z < 5; z++) for (int y = 0; y < 3; y++) {
            lower[0][z][y] = z - y * 0.25;
            upper[0][z][y] = -z + y * 0.75;
        }
        return VanillaTerrainInterpolation.input(4, 8, 2, lower, upper);
    }
}
