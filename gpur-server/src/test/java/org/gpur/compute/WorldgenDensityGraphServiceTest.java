package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.GPurConfig;
import org.gpur.terrain.VanillaDensityGraphBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Service admission and retirement tests; no live NoiseChunk result adoption is implied. */
@VanillaFeature
class WorldgenDensityGraphServiceTest {
    private final Map<Field, Object> previous = new LinkedHashMap<>();

    @BeforeEach
    void configure() throws Exception {
        set("gpuAccelerationEnabled", true);
        set("terrainGpuEnabled", true);
        set("gpuAsyncSubmit", true);
        set("gpuBufferBudgetMiB", 16);
        set("vanillaTerrainEnabled", true);
        set("vanillaTerrainMode", VanillaVerificationPolicy.Mode.STRICT);
        set("vanillaTerrainVerifyEveryBatch", true);
        set("vanillaTerrainMinValues", 1_024);
        set("vanillaTerrainMaxSlabValues", 65_536);
        set("vanillaNoiseGpuEnabled", false);
        set("vanillaAquiferGpuEnabled", false);
        set("vanillaCornerGraphsGpuEnabled", true);
    }

    @AfterEach
    void restore() throws Exception {
        for (var entry : this.previous.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test
    void boundedGraphBatchesHaveIndependentAdmissionVerificationAndConsumption() throws Exception {
        VulkanDevice backend = backend();
        when(backend.computeAsync(any(), anyInt(), anyInt())).thenAnswer(call -> {
            int[] input = call.getArgument(0);
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        int[] input = WorldgenStartupCorpus.densityGraphs().getFirst();
        assertEquals(VanillaDensityGraphBatch.WORKLOAD, input[0]);
        assertTrue(input[1] < GPurConfig.vanillaTerrainMinValues);
        try (ComputeService service = service(backend)) {
            assertFalse(service.worldgenEligible(VanillaNoiseBatch.WORKLOAD));
            assertFalse(service.worldgenEligible(VanillaAquiferBatch.WORKLOAD));
            assertTrue(service.worldgenEligible(VanillaDensityGraphBatch.WORKLOAD));
            WorldgenResult result = service.prepareWorldgenAsync(input, Runnable::run, 128L).join();
            assertNotNull(result);
            assertArrayEquals(ExactCompute.reference(input), result.readWords());
            var status = status(service);
            assertEquals(1, status.dispatches());
            assertEquals(1, status.paritySamples());
            assertEquals(1, status.acceptedBatches());
            assertEquals(input[1], status.computedValues());
            assertEquals(0, status.consumedValues());
            assertTrue(reservedBytes(service) > 0);
            result.recordConsumption(input[1]);
            assertEquals(input[1], status(service).consumedValues());
            result.close();
            result.close();
            assertEquals(0, reservedBytes(service));
            assertEquals(0, service.worldgenFallbacks(VanillaDensityGraphBatch.WORKLOAD));
        }
    }

    @Test
    void optOutNeverSubmitsAndObserveVerifiesWithoutReturningAFrame() throws Exception {
        VulkanDevice disabled = backend();
        GPurConfig.vanillaCornerGraphsGpuEnabled = false;
        int[] input = WorldgenStartupCorpus.densityGraphs().getFirst();
        try (ComputeService service = service(disabled)) {
            assertFalse(service.worldgenEligible(VanillaDensityGraphBatch.WORKLOAD));
            assertNull(service.prepareWorldgenAsync(input, Runnable::run).join());
            verify(disabled, never()).computeAsync(any(), anyInt(), anyInt());
            assertEquals(1, service.worldgenFallbacks(VanillaDensityGraphBatch.WORKLOAD));
            assertEquals(0, reservedBytes(service));
        }
        GPurConfig.vanillaCornerGraphsGpuEnabled = true;
        GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.OBSERVE;
        VulkanDevice observed = backend();
        when(observed.computeAsync(any(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(ExactCompute.reference(input)));
        try (ComputeService service = service(observed)) {
            assertNull(service.prepareWorldgenAsync(input, Runnable::run).join());
            assertEquals(1, status(service).dispatches());
            assertEquals(1, status(service).paritySamples());
            assertEquals(0, status(service).acceptedBatches());
            assertEquals(0, status(service).consumedValues());
            assertEquals(0, reservedBytes(service));
        }
    }

    @Test
    void aBitMismatchQuarantinesAndInvalidatesEarlierGraphFrames() throws Exception {
        VulkanDevice backend = backend();
        AtomicLong dispatch = new AtomicLong();
        when(backend.computeAsync(any(), anyInt(), anyInt())).thenAnswer(call -> {
            int[] input = call.getArgument(0);
            int[] output = ExactCompute.reference(input);
            if (dispatch.incrementAndGet() == 2) output[0] ^= 1;
            return CompletableFuture.completedFuture(output);
        });
        int[] input = WorldgenStartupCorpus.densityGraphs().getFirst();
        try (ComputeService service = service(backend)) {
            WorldgenResult first = service.prepareWorldgenAsync(input, Runnable::run).join();
            assertNotNull(first);
            assertNull(service.prepareWorldgenAsync(input, Runnable::run).join());
            assertNull(first.readWords(), "parity failure retires previously accepted results");
            verify(backend).disable();
            assertEquals(2, status(service).paritySamples());
            assertEquals(1, status(service).acceptedBatches());
            assertEquals(1, service.worldgenFallbacks(VanillaDensityGraphBatch.WORKLOAD));
            first.close();
            assertEquals(0, reservedBytes(service));
        }
    }

    @Test
    void shutdownReleasesPendingGraphInputsAndReturnedGraphFrames() throws Exception {
        int[] input = WorldgenStartupCorpus.densityGraphs().getFirst();
        VulkanDevice backend = backend();
        CompletableFuture<int[]> pending = new CompletableFuture<>();
        when(backend.computeAsync(any(), anyInt(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(ExactCompute.reference(input)), pending);
        ComputeService service = service(backend);
        WorldgenResult returned = service.prepareWorldgenAsync(input, Runnable::run).join();
        assertNotNull(returned);
        CompletableFuture<WorldgenResult> submitted = service.prepareWorldgenAsync(input, Runnable::run).whenComplete((result, failure) -> {
            if (result != null) result.close();
        });
        assertTrue(reservedBytes(service) > 0);
        service.close();
        assertNull(submitted.join());
        assertTrue(pending.isCancelled());
        assertNull(returned.readWords());
        assertEquals(0, reservedBytes(service));
    }

    private void set(String name, Object value) throws Exception {
        Field field = GPurConfig.class.getField(name);
        this.previous.put(field, field.get(null));
        field.set(null, value);
    }

    private static VulkanDevice backend() {
        VulkanDevice device = mock(VulkanDevice.class);
        when(device.available()).thenReturn(true);
        when(device.name()).thenReturn("density-graph-simulated");
        when(device.uuid()).thenReturn("density-graph-simulated-uuid");
        return device;
    }

    private static ComputeService service(VulkanDevice backend) {
        return new ComputeService(Logger.getLogger("density-graph-service"), List.of(backend));
    }

    private static ComputeService.WorldgenDeviceStatus status(ComputeService service) {
        return service.worldgenDevices().stream().filter(status -> status.workload() == VanillaDensityGraphBatch.WORKLOAD)
            .findFirst().orElseThrow();
    }

    private static long reservedBytes(ComputeService service) throws Exception {
        Field field = ComputeService.class.getDeclaredField("vanillaSnapshotBytes");
        field.setAccessible(true);
        return ((AtomicLong)field.get(service)).get();
    }
}
