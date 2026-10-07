package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "gpur.gpu-tests", matches = "true")
class VulkanParityTest {
    @Test void everyPhysicalDevicePassesBoundaryAndConcurrentSnapshotTests() throws Exception {
        var inventory = supportedDevices();
        System.out.println("GPur Vulkan inventory: " + inventory);
        try (var config = new ConfigScope()) {
            for (var info : inventory) {
                try (VulkanDevice device = VulkanDevice.create(info.uuid());
                     var executor = Executors.newFixedThreadPool(8)) {
                    ComputeService.selfTest(device);
                    AtomicInteger completed = new AtomicInteger();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < 64; i++) {
                        final int origin = i;
                        futures.add(executor.submit(() -> {
                            int[] input = origin % 2 == 0 ? distanceSnapshot(origin, 512) : sectionSnapshot(origin, 2);
                            int[] result = device.compute(input, input[1] * (input[0] == 1 ? 2 : 1), input[1]);
                            if (result != null) {
                                assertTrue(ExactCompute.equal(ExactCompute.reference(input), result, input[0]), info.name());
                                completed.incrementAndGet();
                            }
                        }));
                    }
                    for (var future : futures) future.get(30, TimeUnit.SECONDS);
                    assertTrue(completed.get() > 0, "Concurrent tests must actually use " + info.name());
                }
            }
        }
    }

    @Test void eachDeviceCanBeSelectedAloneByUuidAndName() {
        var inventory = supportedDevices();
        try (var config = new ConfigScope()) {
            GPurConfig.multiGpuEnabled = false;
            for (var info : inventory) {
                for (String selector : List.of(info.uuid(), info.name())) {
                    GPurConfig.gpuDevices = List.of(selector);
                    try (var service = new ComputeService(Logger.getLogger("GPur-test"))) {
                        assertEquals(1, service.activeDeviceCount(), selector);
                        var selected = service.deviceWorkloads();
                        // A name can identify multiple identical models; the UUID always identifies one.
                        assertTrue(selected.stream().allMatch(s -> selector.equals(info.uuid())
                            ? s.uuid().equals(info.uuid()) : s.name().equals(info.name())), selector);
                        assertGpuResult(service, distanceSnapshot(0, 1024));
                        assertGpuResult(service, sectionSnapshot(0, 2));
                        assertTrue(service.deviceWorkloads().stream().allMatch(s -> s.gpuResults() == 1), selector);
                        System.out.println("GPur single selector " + selector + ": " + service.status());
                    }
                }
            }
        }
    }

    @Test void mixedDevicesShareIdleWorkAndReturnExactConcurrentResults() throws Exception {
        var inventory = supportedDevices();
        try (var config = new ConfigScope()) {
            // Explicitly repeat a selector to prove one physical UUID is opened only once.
            GPurConfig.gpuDevices = List.of("auto", inventory.getFirst().uuid());
            try (var service = new ComputeService(Logger.getLogger("GPur-test"));
                 var executor = Executors.newFixedThreadPool(12)) {
                assertEquals(inventory.size(), service.activeDeviceCount());
                for (int i = 0; i < inventory.size() * 8; i++) assertGpuResult(service, distanceSnapshot(i, 256));
                for (int i = 0; i < inventory.size() * 8; i++) assertGpuResult(service, sectionSnapshot(i, 2));
                assertTrue(service.deviceWorkloads().stream().allMatch(s -> s.gpuResults() > 0),
                    "Both workloads must run on every selected device: " + service.status());
                AtomicInteger completed = new AtomicInteger();
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 128; i++) {
                    final int origin = i;
                    futures.add(executor.submit(() -> {
                        int[] input = origin % 2 == 0 ? distanceSnapshot(origin, 2048) : sectionSnapshot(origin, 4);
                        int[] result = service.tryCompute(input);
                        if (result != null) {
                            assertTrue(ExactCompute.equal(ExactCompute.reference(input), result, input[0]));
                            completed.incrementAndGet();
                        }
                    }));
                }
                for (var future : futures) future.get(30, TimeUnit.SECONDS);
                assertTrue(completed.get() > 0, "Concurrent tests must complete GPU work");
                System.out.println("GPur mixed GPU: " + service.status());
            }
        }
    }

    @Test void closingDuringInflightWorkKeepsReturnedSnapshotsExactAndRejectsNewWork() throws Exception {
        var inventory = supportedDevices();
        try (var config = new ConfigScope()) {
            GPurConfig.gpuDevices = inventory.stream().map(VulkanDevice.Info::uuid).toList();
            try (var service = new ComputeService(Logger.getLogger("GPur-test"));
                 var executor = Executors.newFixedThreadPool(8)) {
                int[] input = distanceSnapshot(19, 262144);
                int[] expected = ExactCompute.reference(input);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        while (service.activeDeviceCount() > 0) {
                            int[] actual = service.tryCompute(input);
                            if (actual != null) assertTrue(ExactCompute.equal(expected, actual, 1));
                            else Thread.yield();
                        }
                        return null;
                    }));
                }
                start.countDown();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean observed = false;
                    while (!(observed = service.deviceWorkloads().stream().anyMatch(s -> s.busy() > 0)) && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertTrue(observed, "Must observe an in-flight dispatch");
                } finally {
                    // Stop looped workers before the executor's close, including on assertion failure.
                    service.close();
                }
                for (var future : futures) future.get(30, TimeUnit.SECONDS);
                assertEquals(0, service.activeDeviceCount());
                assertFalse(service.eligible(1));
                assertFalse(service.eligible(2));
                assertNull(service.tryCompute(input));
                assertTrue(service.deviceWorkloads().stream().noneMatch(ComputeService.DeviceWorkloadStatus::available));
                service.close(); // Close is idempotent after all dispatches have drained.
            }
        }
    }

    private static List<VulkanDevice.Info> supportedDevices() {
        var inventory = VulkanDevice.discover();
        assertFalse(inventory.isEmpty(), "Hardware tests require Vulkan devices");
        assertEquals(inventory.size(), inventory.stream().map(VulkanDevice.Info::uuid).distinct().count());
        var supported = inventory.stream().filter(VulkanDevice.Info::float64).toList();
        assertFalse(supported.isEmpty(), "Hardware tests require at least one FP64 device");
        return supported;
    }

    private static void assertGpuResult(ComputeService service, int[] input) {
        int[] actual = service.tryCompute(input);
        assertNotNull(actual, service.status());
        assertTrue(ExactCompute.equal(ExactCompute.reference(input), actual, input[0]), service.status());
    }

    private static int[] distanceSnapshot(int origin, int players) {
        double[] positions = new double[players * 3];
        for (int i = 0; i < players; i++) {
            positions[i * 3] = origin + (i % 2 == 0 ? 24.01 : -23.99);
            positions[i * 3 + 1] = -64 + i % 383;
            positions[i * 3 + 2] = 30_000_000 - i;
        }
        return ExactCompute.distances(origin, -64, 30_000_000, positions);
    }

    private static int[] sectionSnapshot(int seed, int sections) {
        int[] input = new int[2 + ExactCompute.SECTION_WORDS * sections];
        input[0] = 2;
        input[1] = sections * 4096;
        // Zero halo leaves eligible blocks fully enclosed. Alternate eligible and visible
        // blocks so the test verifies both 0 and 1 outputs and section indexing.
        for (int section = 0; section < sections; section++) {
            int offset = 2 + section * ExactCompute.SECTION_WORDS;
            for (int block = 0; block < 4096; block++) input[offset + block] = (block + seed + section) % 3 == 0 ? 3 : 1;
        }
        return input;
    }

    private static final class ConfigScope implements AutoCloseable {
        private final boolean acceleration = GPurConfig.gpuAccelerationEnabled;
        private final boolean multi = GPurConfig.multiGpuEnabled;
        private final boolean force = GPurConfig.gpuForce;
        private final List<String> selectors = GPurConfig.gpuDevices;
        private final int contexts = GPurConfig.gpuExecutionContexts;
        private final int usage = GPurConfig.gpuUsageFallback;
        private final int reserved = GPurConfig.antiXrayGpuReservedContexts;

        private ConfigScope() {
            GPurConfig.gpuAccelerationEnabled = true;
            GPurConfig.multiGpuEnabled = true;
            GPurConfig.gpuForce = true; // Parity/scheduling tests explicitly request dispatch, not a speed comparison.
            GPurConfig.gpuDevices = List.of("auto");
            GPurConfig.gpuExecutionContexts = 4;
            GPurConfig.gpuUsageFallback = 90;
            GPurConfig.antiXrayGpuReservedContexts = 1;
        }

        @Override public void close() {
            GPurConfig.gpuAccelerationEnabled = this.acceleration;
            GPurConfig.multiGpuEnabled = this.multi;
            GPurConfig.gpuForce = this.force;
            GPurConfig.gpuDevices = this.selectors;
            GPurConfig.gpuExecutionContexts = this.contexts;
            GPurConfig.gpuUsageFallback = this.usage;
            GPurConfig.antiXrayGpuReservedContexts = this.reserved;
        }
    }
}
