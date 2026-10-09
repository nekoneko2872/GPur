package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldgenResultLifecycleTest {
    private final ConfigSnapshot previous = ConfigSnapshot.capture();

    @BeforeEach
    void configureStrictWorldgen() {
        GPurConfig.gpuAccelerationEnabled = true;
        GPurConfig.terrainGpuEnabled = true;
        GPurConfig.gpuAsyncSubmit = true;
        GPurConfig.gpuBufferBudgetMiB = 16;
        GPurConfig.vanillaTerrainEnabled = true;
        GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.STRICT;
        GPurConfig.vanillaTerrainVerifyEveryBatch = true;
        GPurConfig.vanillaTerrainMinValues = 1;
        GPurConfig.vanillaTerrainMaxSlabValues = 65_536;
        GPurConfig.vanillaNoiseGpuEnabled = true;
        GPurConfig.vanillaAquiferGpuEnabled = true;
    }

    @AfterEach
    void restoreConfig() {
        this.previous.restore();
    }

    @Test
    void strictWorkloadsVerifyBeforeReturnAndTrackConsumptionSeparately() throws Exception {
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });

        try (ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device))) {
            for (int workload : new int[] {VanillaNoiseBatch.WORKLOAD, VanillaAquiferBatch.WORKLOAD}) {
                int[] input = input(workload);
                long ownerBytes = 37L;
                WorldgenResult result = service.prepareWorldgenAsync(input, Runnable::run, ownerBytes).join();
                assertNotNull(result, "strict mode should adopt an exactly verified workload " + workload);
                assertTrue(result.usable());
                assertEquals(ExactCompute.outputWords(input), result.wordCount());
                assertEquals(ExactCompute.reference(input)[0], result.word(0));

                var status = status(service, workload);
                assertEquals(1, status.dispatches());
                assertEquals(1, status.paritySamples());
                assertEquals(1, status.acceptedBatches());
                assertEquals(input[1], status.computedValues());
                assertEquals(0, status.consumedValues(), "returning a numeric frame is not CPU consumption");
                assertEquals(retainedBytes(input, ownerBytes), reservedBytes(service));

                result.recordConsumption(11L + workload);
                assertEquals(11L + workload, status(service, workload).consumedValues());
                result.close();
                result.close();
                assertFalse(result.usable());
                assertEquals(0, reservedBytes(service), "closing a returned frame releases its owner charge exactly once");
                result.recordConsumption(1L);
                assertEquals(11L + workload, status(service, workload).consumedValues(), "closed frames cannot accrue consumption");
            }
        }
    }

    @Test
    void strictVerificationRejectsMismatchesForBothNoiseAndAquiferWorkloads() throws Exception {
        for (int workload : new int[] {VanillaNoiseBatch.WORKLOAD, VanillaAquiferBatch.WORKLOAD}) {
            VulkanDevice device = backend();
            when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
                int[] input = invocation.getArgument(0);
                int[] words = ExactCompute.reference(input);
                words[0] ^= 1;
                return CompletableFuture.completedFuture(words);
            });

            try (ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device))) {
                assertNull(service.prepareWorldgenAsync(input(workload), Runnable::run).join());
                var status = status(service, workload);
                assertEquals(1, status.dispatches());
                assertEquals(1, status.paritySamples());
                assertEquals(0, status.acceptedBatches());
                assertEquals(0, status.computedValues());
                assertEquals(0, status.consumedValues());
                assertEquals(1, service.worldgenFallbacks(workload));
                verify(device).disable();
                assertEquals(0, reservedBytes(service));
            }
        }
    }

    @Test
    void aLaterStrictMismatchInvalidatesPreviouslyReturnedFramesByEpoch() throws Exception {
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            int[] words = ExactCompute.reference(input);
            if (input[0] == VanillaAquiferBatch.WORKLOAD) words[0] ^= 1;
            return CompletableFuture.completedFuture(words);
        });

        try (ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device))) {
            int[] acceptedInput = input(VanillaNoiseBatch.WORKLOAD);
            WorldgenResult accepted = service.prepareWorldgenAsync(acceptedInput, Runnable::run, 19L).join();
            assertNotNull(accepted);
            assertTrue(accepted.usable());
            assertTrue(reservedBytes(service) > 0);

            assertNull(service.prepareWorldgenAsync(input(VanillaAquiferBatch.WORKLOAD), Runnable::run, 23L).join());
            assertFalse(accepted.usable(), "a parity failure on any worldgen workload invalidates old-epoch frames");
            assertNull(accepted.readWords());
            assertEquals(1, service.worldgenFallbacks(VanillaAquiferBatch.WORKLOAD));

            accepted.close();
            assertEquals(0, reservedBytes(service));
        }
    }

    @Test
    void cancellingPendingSubmissionReleasesItsSnapshotBudget() throws Exception {
        VulkanDevice device = backend();
        CompletableFuture<int[]> raw = new CompletableFuture<>();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(raw);
        int[] input = input(VanillaNoiseBatch.WORKLOAD);
        long ownerBytes = 41L;

        try (ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device))) {
            CompletableFuture<WorldgenResult> pending = service.prepareWorldgenAsync(input, Runnable::run, ownerBytes);
            assertEquals(retainedBytes(input, ownerBytes), reservedBytes(service));
            assertTrue(pending.cancel(false));
            assertEquals(0, reservedBytes(service));
            assertTrue(raw.isCancelled(), "cancelling the owner future cancels an uncompleted backend result");
        }
    }

    @Test
    void closingServiceInvalidatesAndReleasesReturnedFrames() throws Exception {
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device));
        WorldgenResult result = service.prepareWorldgenAsync(input(VanillaNoiseBatch.WORKLOAD), Runnable::run, 29L).join();
        assertNotNull(result);
        assertTrue(result.usable());
        int[] localLease = result.readWords();
        assertNotNull(localLease);
        int[] leaseSnapshot = localLease.clone();
        assertTrue(reservedBytes(service) > 0);

        service.close();
        assertFalse(result.usable());
        assertNull(result.readWords());
        assertEquals(0, result.wordCount());
        assertArrayEquals(leaseSnapshot, localLease, "a caller's local read lease remains stable after concurrent close");
        assertEquals(0, reservedBytes(service));
        result.close();
        assertEquals(0, reservedBytes(service));
    }

    @Test
    void rejectedContinuationCompletesExceptionallyAndReleasesSnapshotBudget() throws Exception {
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        Executor rejected = command -> { throw new RejectedExecutionException("test executor is closed"); };
        int[] input = input(VanillaAquiferBatch.WORKLOAD);

        try (ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device))) {
            CompletableFuture<WorldgenResult> result = service.prepareWorldgenAsync(input, rejected, 53L);
            assertThrows(CompletionException.class, result::join);
            assertEquals(0, reservedBytes(service));
            assertEquals(0, status(service, VanillaAquiferBatch.WORKLOAD).acceptedBatches());
        }
    }

    @Test
    void closingAndDiscardingQueuedCpuContinuationReleasesPendingSnapshotBudget() throws Exception {
        VulkanDevice device = backend();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        ArrayDeque<Runnable> continuationQueue = new ArrayDeque<>();
        int[] input = input(VanillaNoiseBatch.WORKLOAD);
        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device));
        CompletableFuture<WorldgenResult> pending = service.prepareWorldgenAsync(input, continuationQueue::add, 31L);
        assertEquals(retainedBytes(input, 31L), reservedBytes(service));
        assertEquals(1, continuationQueue.size());

        service.close();
        assertNull(pending.join());
        assertEquals(1, continuationQueue.size(), "the test intentionally discards the queued continuation");
        continuationQueue.clear();
        assertEquals(0, reservedBytes(service));
        assertEquals(0, status(service, VanillaNoiseBatch.WORKLOAD).acceptedBatches());
    }

    @Test
    void trackedVanillaGenerationReturnsSameFutureAndDropsItAfterNormalCompletion() {
        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of());
        TrackingFuture<Integer> generation = new TrackingFuture<>();

        assertSame(generation, service.trackVanillaGeneration(generation));
        assertTrue(generation.complete(42));
        assertEquals(42, generation.join());

        service.close();
        assertEquals(0, generation.cancelCalls, "completed generations are removed from service cancellation tracking");
        assertEquals(42, generation.join());
    }

    @Test
    void registrationAfterServiceCloseCancelsBeforeAnyWorldMutation() {
        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of());
        service.close();

        AtomicLong worldMutations = new AtomicLong();
        CompletableFuture<Integer> lateGeneration = new CompletableFuture<>();
        lateGeneration.thenAccept(ignored -> worldMutations.incrementAndGet());

        assertSame(lateGeneration, service.trackVanillaGeneration(lateGeneration));
        assertTrue(lateGeneration.isCancelled(), "a closed service must reject newly registered generation work");
        assertFalse(lateGeneration.complete(1));
        assertEquals(0, worldMutations.get());
    }

    @Test
    void reloadReturnsPendingNumericWorkToCpuAndKeepsTrackedOwnerAlive() throws Exception {
        VulkanDevice device = backend();
        CompletableFuture<int[]> raw = new CompletableFuture<>();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenReturn(raw);

        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of(device));
        CompletableFuture<int[]> numeric = service.tryVanillaComputeAsync(terrainInput(), Runnable::run);
        CompletableFuture<String> generation = service.trackVanillaGeneration(
            numeric.thenApply(words -> words == null ? "cpu" : "gpu")
        );

        service.retireForReload();

        assertNull(numeric.join(), "retiring completes pending numeric submissions with the CPU-fallback sentinel");
        assertEquals("cpu", generation.join(), "the existing chunk owner resumes its CPU branch");
        assertFalse(generation.isCancelled(), "live reload must let registered owners finish");
        assertTrue(raw.isCancelled(), "the retired numeric GPU request is no longer needed");
        assertTrue(service.retirementCompletion().isDone(), "retirement drains after the tracked generation finishes");
    }

    @Test
    void reloadContinuationScopeAllowsLateOwnerRegistrationAndDrainsOnlyAfterReleaseAndCompletion() {
        ComputeService service = new ComputeService(Logger.getLogger("worldgen-lifecycle"), List.of());
        assertTrue(service.tryAcquireVanillaContinuation(), "the in-flight generator setup must acquire a reload lease");
        CompletableFuture<Integer> earlyOwner = new CompletableFuture<>();
        assertSame(earlyOwner, service.trackVanillaGeneration(earlyOwner));

        service.retireForReload();
        assertFalse(earlyOwner.isCancelled(), "reload preserves owners admitted before retirement");
        assertFalse(service.retirementCompletion().isDone(), "the acquired setup scope keeps retirement open");

        CompletableFuture<Integer> lateOwner = new CompletableFuture<>();
        assertSame(lateOwner, service.trackVanillaGeneration(lateOwner));
        assertFalse(lateOwner.isCancelled(), "a scope acquired before retirement permits its late owner registration");
        service.releaseVanillaContinuation();

        assertFalse(service.retirementCompletion().isDone(), "registered owners still hold retirement after setup releases");
        assertTrue(earlyOwner.complete(1));
        assertFalse(service.retirementCompletion().isDone(), "all owners must drain, not merely the first one");
        assertTrue(lateOwner.complete(2));
        assertDoesNotThrow(() -> service.retirementCompletion().join());
    }

    private static VulkanDevice backend() {
        VulkanDevice device = mock(VulkanDevice.class);
        AtomicBoolean available = new AtomicBoolean(true);
        when(device.available()).thenAnswer(ignored -> available.get());
        when(device.uuid()).thenReturn("simulated-worldgen");
        when(device.name()).thenReturn("Simulated worldgen GPU");
        doAnswer(ignored -> { available.set(false); return null; }).when(device).disable();
        return device;
    }

    private static int[] input(int workload) {
        if (workload == VanillaNoiseBatch.WORKLOAD) {
            byte[] permutation = new byte[256];
            for (int i = 0; i < permutation.length; i++) permutation[i] = (byte)i;
            VanillaNoiseBatch.ImprovedNoiseProfile profile = new VanillaNoiseBatch.ImprovedNoiseProfile(1.25, -2.5, 3.75, permutation);
            return VanillaNoiseBatch.input(new VanillaNoiseBatch.NoiseSample(profile, -17.25, 2.5, 91.125, 0.25, 0.125));
        }
        int[] centers = {
            -24,-32,-24, -12,-32,-24, -24,-32,-12, -12,-32,-12,
            -24,-20,-24, -12,-20,-24, -24,-20,-12, -12,-20,-12,
            -24,-8,-24, -12,-8,-24, -24,-8,-12, -12,-8,-12
        };
        return VanillaAquiferBatch.input(-2, -3, -2, 2, 3, 2, new int[] {-18, -18, -18}, centers);
    }

    private static int[] terrainInput() {
        double[][][] lower = new double[1][5][3];
        double[][][] upper = new double[1][5][3];
        for (int z = 0; z < 5; z++) for (int y = 0; y < 3; y++) {
            lower[0][z][y] = z - y * 0.25;
            upper[0][z][y] = -z + y * 0.75;
        }
        return VanillaTerrainInterpolation.input(4, 8, 2, lower, upper);
    }

    private static ComputeService.WorldgenDeviceStatus status(ComputeService service, int workload) {
        return service.worldgenDevices().stream().filter(status -> status.workload() == workload).findFirst().orElseThrow();
    }

    private static long reservedBytes(ComputeService service) throws Exception {
        Field field = ComputeService.class.getDeclaredField("vanillaSnapshotBytes");
        field.setAccessible(true);
        return ((AtomicLong)field.get(service)).get();
    }

    private static long retainedBytes(int[] input, long ownerBytes) {
        return ownerBytes + ((long)input.length + ExactCompute.outputWords(input)) * Integer.BYTES;
    }

    private static final class TrackingFuture<T> extends CompletableFuture<T> {
        private int cancelCalls;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelCalls++;
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private record ConfigSnapshot(
        boolean gpuAccelerationEnabled,
        boolean terrainGpuEnabled,
        boolean gpuAsyncSubmit,
        int gpuBufferBudgetMiB,
        boolean vanillaTerrainEnabled,
        VanillaVerificationPolicy.Mode vanillaTerrainMode,
        boolean vanillaTerrainVerifyEveryBatch,
        int vanillaTerrainMinValues,
        int vanillaTerrainMaxSlabValues,
        boolean vanillaNoiseGpuEnabled,
        boolean vanillaAquiferGpuEnabled
    ) {
        static ConfigSnapshot capture() {
            return new ConfigSnapshot(
                GPurConfig.gpuAccelerationEnabled,
                GPurConfig.terrainGpuEnabled,
                GPurConfig.gpuAsyncSubmit,
                GPurConfig.gpuBufferBudgetMiB,
                GPurConfig.vanillaTerrainEnabled,
                GPurConfig.vanillaTerrainMode,
                GPurConfig.vanillaTerrainVerifyEveryBatch,
                GPurConfig.vanillaTerrainMinValues,
                GPurConfig.vanillaTerrainMaxSlabValues,
                GPurConfig.vanillaNoiseGpuEnabled,
                GPurConfig.vanillaAquiferGpuEnabled
            );
        }

        void restore() {
            GPurConfig.gpuAccelerationEnabled = this.gpuAccelerationEnabled;
            GPurConfig.terrainGpuEnabled = this.terrainGpuEnabled;
            GPurConfig.gpuAsyncSubmit = this.gpuAsyncSubmit;
            GPurConfig.gpuBufferBudgetMiB = this.gpuBufferBudgetMiB;
            GPurConfig.vanillaTerrainEnabled = this.vanillaTerrainEnabled;
            GPurConfig.vanillaTerrainMode = this.vanillaTerrainMode;
            GPurConfig.vanillaTerrainVerifyEveryBatch = this.vanillaTerrainVerifyEveryBatch;
            GPurConfig.vanillaTerrainMinValues = this.vanillaTerrainMinValues;
            GPurConfig.vanillaTerrainMaxSlabValues = this.vanillaTerrainMaxSlabValues;
            GPurConfig.vanillaNoiseGpuEnabled = this.vanillaNoiseGpuEnabled;
            GPurConfig.vanillaAquiferGpuEnabled = this.vanillaAquiferGpuEnabled;
        }
    }
}
