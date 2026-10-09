package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.gpur.compute.VulkanDevice.DeviceMetrics;
import org.gpur.compute.VulkanDevice.Info;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in physical-device checks for async workload-4 admission, readback, and lifecycle. */
@EnabledIfSystemProperty(named = "gpur.gpu-tests", matches = "true")
final class VulkanTerrainHardwareTest {
    private static final int TERRAIN_OUTPUT_BYTES = 64 * 1024;
    private static final int MIXED_REQUESTS = 32;

    @Test
    void everyPhysicalDevicePassesNativeAsyncTerrainAndMixedStrictLifecycle() throws Exception {
        ConfigSnapshot config = ConfigSnapshot.capture();
        try {
            configureBase();
            List<Info> devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
            assertFalse(devices.isEmpty(), "Hardware test requires Vulkan FP64 devices");
            assertEquals(devices.size(), devices.stream().map(Info::uuid).distinct().count());

            int[] largeInput = terrain64KiBOutput();
            int[] expectedLarge = ExactCompute.reference(largeInput);
            assertEquals(TERRAIN_OUTPUT_BYTES, expectedLarge.length * Integer.BYTES);
            for (Info info : devices) {
                verifyNativeMode(info, 0, false, largeInput, expectedLarge);
                verifyNativeMode(info, TERRAIN_OUTPUT_BYTES, true, largeInput, expectedLarge);
            }

            configureMixed(devices);
            verifyMixedStrictAndClose(devices, largeInput, expectedLarge);
        } finally {
            config.restore();
        }
    }

    private static void verifyNativeMode(Info info, int stagingThreshold, boolean expectStaging,
                                         int[] largeInput, int[] expectedLarge) throws Exception {
        GPurConfig.multiGpuEnabled = false;
        GPurConfig.gpuDevices = List.of(info.uuid());
        GPurConfig.gpuExecutionContexts = 1;
        GPurConfig.gpuDeviceLocalThresholdBytes = stagingThreshold;
        try (VulkanDevice device = VulkanDevice.create(info.uuid())) {
            assertTrue(device.available(), info.name());
            ComputeService.selfTest(device);
            DeviceMetrics before = device.metrics();

            int[] mutableInput = largeInput.clone();
            CompletableFuture<int[]> large = device.computeAsync(
                mutableInput, ExactCompute.outputWords(mutableInput), mutableInput[1]
            );
            Arrays.fill(mutableInput, 0); // The accepted native job must own its immutable snapshot.
            assertExact(largeInput, expectedLarge, large.get(30, TimeUnit.SECONDS), info.name() + " large async");
            assertEquals(expectStaging, deviceLocalMode(device), info.name() + " staging mode");

            List<int[]> corpus = ExactStartupCorpus.workloads();
            assertEquals(8, corpus.size(), "The complete startup corpus must run on each device and memory path");
            List<CompletableFuture<int[]>> pending = new ArrayList<>(corpus.size());
            List<int[]> expected = new ArrayList<>(corpus.size());
            for (int[] input : corpus) {
                expected.add(ExactCompute.reference(input));
                pending.add(device.computeAsync(input, ExactCompute.outputWords(input), input[1]));
            }
            for (int i = 0; i < pending.size(); i++) {
                assertExact(corpus.get(i), expected.get(i), pending.get(i).get(30, TimeUnit.SECONDS),
                    info.name() + " startup corpus case " + i);
            }

            DeviceMetrics after = device.metrics();
            long nativeJobs = after.submittedJobs() - before.submittedJobs();
            assertEquals(9, nativeJobs, info.name() + " native large+corpus jobs");
            assertEquals(nativeJobs, after.completedJobs() - before.completedJobs(), info.name() + " native completions");
            assertEquals(0, after.rejectedJobs() - before.rejectedJobs(), info.name() + " rejected jobs");
            assertEquals(0, after.timedOutJobs() - before.timedOutJobs(), info.name() + " timed out jobs");
            assertEquals(0, after.failedJobs() - before.failedJobs(), info.name() + " failed jobs");
            assertTrue(device.available(), info.name());
            System.out.println("GPur native terrain " + info.name() + " mode=" + (expectStaging ? "staging" : "direct")
                + " outputBytes=" + TERRAIN_OUTPUT_BYTES + " startupCorpus=8 nativeJobs=" + nativeJobs
                + " completed=" + (after.completedJobs() - before.completedJobs())
                + " rejected=" + (after.rejectedJobs() - before.rejectedJobs())
                + " timedOut=" + (after.timedOutJobs() - before.timedOutJobs())
                + " failed=" + (after.failedJobs() - before.failedJobs()) + " residentBytes=" + after.residentBytes());
        }
    }

    private static void verifyMixedStrictAndClose(List<Info> devices, int[] largeInput, int[] expectedLarge) throws Exception {
        try (ComputeService service = new ComputeService(Logger.getLogger("GPur-hardware-test"));
             ExecutorService verification = Executors.newFixedThreadPool(2)) {
            assertEquals(devices.size(), service.activeDeviceCount(), service.status());
            assertTrue(service.vanillaTerrainAsyncEligible());
            ComputeService.VanillaStatus initial = service.vanillaTerrainStatus();
            assertEquals("strict", initial.mode());
            Map<String, DeviceMetrics> baseline = initial.devices().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(ComputeService.VanillaDeviceStatus::uuid,
                    status -> java.util.Objects.requireNonNull(status.backend()))
            );

            List<CompletableFuture<int[]>> pending = new ArrayList<>(MIXED_REQUESTS + 1);
            int[] mutableInput = largeInput.clone();
            pending.add(service.tryVanillaComputeAsync(mutableInput, verification));
            Arrays.fill(mutableInput, 0);
            for (int i = 0; i < MIXED_REQUESTS; i++) pending.add(service.tryVanillaComputeAsync(largeInput, verification));
            for (int i = 0; i < pending.size(); i++) {
                assertExact(largeInput, expectedLarge, pending.get(i).get(30, TimeUnit.SECONDS), "mixed strict terrain request " + i);
            }

            ComputeService.VanillaStatus completed = service.vanillaTerrainStatus();
            assertEquals(0, completed.parityFailures());
            assertEquals(initial.cpuFallbacks(), completed.cpuFallbacks(), "Every mixed terrain request must return a GPU result");
            assertEquals(devices.size(), completed.devices().size());
            assertEquals(MIXED_REQUESTS + 1L, completed.devices().stream().mapToLong(ComputeService.VanillaDeviceStatus::slabs).sum());
            for (Info info : devices) {
                ComputeService.VanillaDeviceStatus status = completed.devices().stream()
                    .filter(candidate -> candidate.uuid().equals(info.uuid())).findFirst()
                    .orElseThrow(() -> new AssertionError("Missing mixed service status for " + info.name()));
                DeviceMetrics before = baseline.get(info.uuid());
                DeviceMetrics after = java.util.Objects.requireNonNull(status.backend());
                long nativeSubmitted = after.submittedJobs() - before.submittedJobs();
                long nativeCompleted = after.completedJobs() - before.completedJobs();
                assertTrue(status.slabs() > 0, info.name() + " must return accepted mixed workload-4 GPU results");
                assertTrue(status.paritySamples() > 0, info.name() + " strict CPU verification must run");
                assertEquals(status.slabs(), nativeSubmitted, info.name() + " native queued jobs");
                assertEquals(status.slabs(), nativeCompleted, info.name() + " native completed jobs");
                assertEquals(0, after.rejectedJobs() - before.rejectedJobs(), info.name() + " mixed rejected jobs");
                assertEquals(0, after.timedOutJobs() - before.timedOutJobs(), info.name() + " mixed timeouts");
                assertEquals(0, after.failedJobs() - before.failedJobs(), info.name() + " mixed failed jobs");
                System.out.println("GPur mixed strict terrain " + info.name() + " uuid=" + info.uuid()
                    + " acceptedResults=" + status.slabs() + " nativeSubmitted=" + (after.submittedJobs() - before.submittedJobs())
                    + " nativeCompleted=" + (after.completedJobs() - before.completedJobs())
                    + " paritySamples=" + status.paritySamples() + " rejected=" + (after.rejectedJobs() - before.rejectedJobs())
                    + " timedOut=" + (after.timedOutJobs() - before.timedOutJobs())
                    + " failed=" + (after.failedJobs() - before.failedJobs()));
            }

            CountDownLatch releaseVerification = new CountDownLatch(1);
            CountDownLatch bothWorkersBlocked = new CountDownLatch(2);
            List<CompletableFuture<int[]>> closing = new ArrayList<>(4);
            try {
                for (int worker = 0; worker < 2; worker++) {
                    verification.execute(() -> {
                        bothWorkersBlocked.countDown();
                        try {
                            releaseVerification.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                assertTrue(bothWorkersBlocked.await(10, TimeUnit.SECONDS), "Both CPU verification workers must be held");
                long nativeCompletedBeforeClose = completed.devices().stream().mapToLong(status -> status.backend().completedJobs()).sum();
                for (int i = 0; i < 4; i++) closing.add(service.tryVanillaComputeAsync(largeInput, verification));
                waitForNativeCompletions(service, nativeCompletedBeforeClose + closing.size());
                assertTrue(closing.stream().noneMatch(CompletableFuture::isDone),
                    "Callbacks must remain pending on the held verification executor before close");
                service.close();
            } finally {
                releaseVerification.countDown();
            }
            for (CompletableFuture<int[]> future : closing) {
                assertNull(future.get(30, TimeUnit.SECONDS), "Close invalidates workload-4 results awaiting verification");
            }
            ComputeService.VanillaStatus closed = service.vanillaTerrainStatus();
            long submittedAtClose = closed.devices().stream().mapToLong(status -> status.backend().submittedJobs()).sum();
            long completedAtClose = closed.devices().stream().mapToLong(status -> status.backend().completedJobs()).sum();
            assertEquals(closing.size(), submittedAtClose - completed.devices().stream().mapToLong(status -> status.backend().submittedJobs()).sum());
            assertEquals(closing.size(), completedAtClose - completed.devices().stream().mapToLong(status -> status.backend().completedJobs()).sum());
            assertEquals(0, closed.devices().stream().mapToLong(status -> status.backend().rejectedJobs()).sum()
                - completed.devices().stream().mapToLong(status -> status.backend().rejectedJobs()).sum());
            assertEquals(0, closed.devices().stream().mapToLong(status -> status.backend().timedOutJobs()).sum()
                - completed.devices().stream().mapToLong(status -> status.backend().timedOutJobs()).sum());
            assertEquals(0, closed.devices().stream().mapToLong(status -> status.backend().failedJobs()).sum()
                - completed.devices().stream().mapToLong(status -> status.backend().failedJobs()).sum());
            System.out.println("GPur close-pending terrain nativeSubmitted=" + (submittedAtClose
                - completed.devices().stream().mapToLong(status -> status.backend().submittedJobs()).sum())
                + " nativeCompleted=" + (completedAtClose
                - completed.devices().stream().mapToLong(status -> status.backend().completedJobs()).sum())
                + " cpuVerificationPending=4 returnedNull=4 rejected=0 timedOut=0 failed=0");
            assertEquals(0, service.activeDeviceCount());
            assertFalse(service.vanillaTerrainAsyncEligible());
            assertNull(service.tryVanillaComputeAsync(largeInput, verification).get(10, TimeUnit.SECONDS));
        }
    }

    private static void waitForNativeCompletions(ComputeService service, long requiredCompletedJobs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            long completed = service.vanillaTerrainStatus().devices().stream()
                .map(ComputeService.VanillaDeviceStatus::backend).filter(java.util.Objects::nonNull)
                .mapToLong(DeviceMetrics::completedJobs).sum();
            if (completed >= requiredCompletedJobs) return;
            Thread.sleep(1);
        }
        fail("Native workload-4 jobs did not drain before close");
    }

    private static boolean deviceLocalMode(VulkanDevice device) throws Exception {
        Field contextsField = VulkanDevice.class.getDeclaredField("contexts");
        contextsField.setAccessible(true);
        BlockingQueue<?> contexts = (BlockingQueue<?>)contextsField.get(device);
        assertEquals(1, contexts.size(), "The single execution context must return after readback");
        Object context = contexts.peek();
        Method deviceLocal = context.getClass().getDeclaredMethod("deviceLocal");
        deviceLocal.setAccessible(true);
        return (boolean)deviceLocal.invoke(context);
    }

    private static void assertExact(int[] input, int[] expected, int[] actual, String label) {
        assertNotNull(actual, label + " must produce a native GPU result");
        assertEquals(ExactCompute.outputWords(input), actual.length, label + " output words");
        assertArrayEquals(expected, actual, label + " all output words");
        assertTrue(ExactCompute.equal(expected, actual, VanillaTerrainInterpolation.WORKLOAD), label + " exact parity");
    }

    private static int[] terrain64KiBOutput() {
        int cellCountY = 256;
        double[][][] lower = new double[1][17][cellCountY + 1];
        double[][][] upper = new double[1][17][cellCountY + 1];
        for (int z = 0; z < 17; z++) {
            for (int y = 0; y <= cellCountY; y++) {
                lower[0][z][y] = ((z * 37 + y * 19) % 2001 - 1000) / 17.0;
                upper[0][z][y] = ((z * 61 - y * 23) % 1999 - 999) / 13.0;
            }
        }
        return VanillaTerrainInterpolation.input(1, 1, cellCountY, lower, upper);
    }

    private static void configureBase() {
        GPurConfig.gpuAccelerationEnabled = true;
        GPurConfig.terrainGpuEnabled = true;
        GPurConfig.gpuForce = true;
        GPurConfig.gpuTimeoutMillis = 2_000;
        GPurConfig.gpuExecutionContexts = 1;
        GPurConfig.gpuQueueThreshold = 1;
        GPurConfig.gpuUsageFallback = 100;
        GPurConfig.gpuCacheEnabled = false;
        GPurConfig.gpuAsyncSubmit = true;
        GPurConfig.gpuBatchMaxJobs = 4;
        GPurConfig.gpuBatchWaitMicros = 200;
        GPurConfig.gpuAsyncQueueCapacity = 32;
        GPurConfig.gpuBufferBudgetMiB = 128;
        GPurConfig.antiXrayGpuReservedContexts = 0;
        GPurConfig.vanillaTerrainEnabled = true;
        GPurConfig.vanillaTerrainVerifyEveryBatch = true;
        GPurConfig.vanillaTerrainMaxInterpolators = 16;
        GPurConfig.vanillaTerrainMaxSlabValues = 1_048_576;
        GPurConfig.vanillaTerrainMinValues = 1;
        GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.STRICT;
        GPurConfig.gpuFullFirstBatches = 16;
        GPurConfig.gpuVerificationSampleOneIn = 1;
    }

    private static void configureMixed(List<Info> devices) {
        GPurConfig.multiGpuEnabled = true;
        GPurConfig.gpuDevices = devices.stream().map(Info::uuid).toList();
        GPurConfig.gpuExecutionContexts = 4;
        GPurConfig.gpuDeviceLocalThresholdBytes = TERRAIN_OUTPUT_BYTES;
    }

    private static final class ConfigSnapshot {
        private final List<SavedField> fields;

        private ConfigSnapshot(List<SavedField> fields) {
            this.fields = fields;
        }

        private static ConfigSnapshot capture() throws IllegalAccessException {
            List<SavedField> fields = new ArrayList<>();
            for (Field field : GPurConfig.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(modifiers) || java.lang.reflect.Modifier.isFinal(modifiers)) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof List<?> list) value = List.copyOf(list);
                fields.add(new SavedField(field, value));
            }
            return new ConfigSnapshot(List.copyOf(fields));
        }

        private void restore() throws IllegalAccessException {
            for (SavedField saved : this.fields) saved.field().set(null, saved.value());
        }
    }

    private record SavedField(Field field, Object value) {}
}
