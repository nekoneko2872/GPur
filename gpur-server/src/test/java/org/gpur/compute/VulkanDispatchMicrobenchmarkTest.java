package org.gpur.compute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.gpur.GPurConfig;
import org.gpur.compute.VulkanDevice.DeviceMetrics;
import org.gpur.compute.VulkanDevice.Info;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Opt-in dispatch microbenchmark. This does not start a server or generate chunks. */
@EnabledIfSystemProperty(named = "gpur.gpu-benchmark", matches = "true")
final class VulkanDispatchMicrobenchmarkTest {
    private static final int WARMUP_ITERATIONS = 16;
    private static final int MAX_PARALLEL_REQUESTS = 4;
    private static final int DEFAULT_ITERATIONS = 100;
    private static final long DENSITY_SEED = 0x4750757254657272L;

    @Test
    void benchmarkExactWorkloadFourDispatches() throws Exception {
        List<Info> fp64Devices = VulkanDevice.discover().stream().filter(Info::float64).toList();
        Info selected = selectDevice(fp64Devices, System.getProperty("gpur.benchmark.device", "auto"));
        assertNotNull(selected, "No matching Vulkan FP64 physical device; set gpur.benchmark.device to a UUID, name, or auto");

        int iterations = measuredIterations();
        Path moduleRoot = findModuleRoot();
        Path configPath = moduleRoot.resolve("build/reports/tmpcfg/gpur.yml");
        Files.createDirectories(configPath.getParent());

        ConfigSnapshot configSnapshot = ConfigSnapshot.capture();
        VulkanDevice device = null;
        try {
            Files.writeString(configPath, benchmarkConfig(selected.uuid()));
            GPurConfig.init(configPath.toFile());

            device = VulkanDevice.create(selected.uuid());
            assertTrue(device.available(), "Selected Vulkan device did not become available");

            // These exact checks run before any performance measurements for every invocation.
            ComputeService.selfTest(device);
            verifyStartupCorpus(device);

            List<WorkloadReport> reports = new ArrayList<>();
            for (Workload workload : workloadMatrix()) {
                reports.add(measure(device, workload, iterations));
            }

            BenchmarkReport report = new BenchmarkReport(
                Instant.now().toString(),
                device.uuid(),
                device.name(),
                "Vulkan 1.1",
                "Exact workload-4 API dispatch only; CPU wire reference is not vanilla chunk-generation timing.",
                WARMUP_ITERATIONS,
                iterations,
                MAX_PARALLEL_REQUESTS,
                reports
            );
            Path reportDirectory = moduleRoot.resolve("build/reports/gpur-terrain-micro");
            Files.createDirectories(reportDirectory);
            String safeUuid = device.uuid().replaceAll("[^A-Za-z0-9._-]", "_");
            Path reportPath = reportDirectory.resolve(safeUuid + ".json");
            Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
            Files.writeString(reportPath, gson.toJson(report));
            System.out.println("GPur terrain dispatch microbenchmark report: " + reportPath.toAbsolutePath());
        } finally {
            try {
                if (device != null) device.close();
            } finally {
                configSnapshot.restore();
            }
        }
    }

    private static Info selectDevice(List<Info> fp64Devices, String selector) {
        if (fp64Devices.isEmpty()) fail("No Vulkan physical device reports shaderFloat64 support");
        if (selector.equalsIgnoreCase("auto")) return fp64Devices.getFirst();
        return fp64Devices.stream()
            .filter(info -> selector.equalsIgnoreCase(info.uuid()) || selector.equalsIgnoreCase(info.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No FP64 Vulkan device matched selector '" + selector + "'"));
    }

    private static int measuredIterations() {
        String property = System.getProperty("gpur.gpu-benchmark.iterations");
        if (property == null || property.isBlank()) return DEFAULT_ITERATIONS;
        try {
            int requested = Integer.parseInt(property.trim());
            return Math.max(1, Math.min(1000, requested));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("gpur.gpu-benchmark.iterations must be an integer", invalid);
        }
    }

    private static void verifyStartupCorpus(VulkanDevice device) {
        for (int[] input : ExactStartupCorpus.workloads()) {
            int[] expected = ExactCompute.reference(input);
            int outputWords = ExactCompute.outputWords(input);
            int[] actual = device.compute(input, outputWords, input[1]);
            assertTrue(ExactCompute.equal(expected, actual, VanillaTerrainInterpolation.WORKLOAD),
                "Vulkan failed exact startup corpus workload");
        }
    }

    private static WorkloadReport measure(VulkanDevice device, Workload workload, int iterations) throws Exception {
        int[] input = createInput(workload);
        if (input[1] != workload.invocationCount()) {
            throw new AssertionError("Workload matrix invocation count did not match its encoded input: " + workload.label());
        }
        int outputWords = ExactCompute.outputWords(input);
        long cpuStart = System.nanoTime();
        int[] expected = ExactCompute.reference(input);
        long cpuWireReferenceNanos = System.nanoTime() - cpuStart;

        // Warm up the driver and dispatch queues, checking every returned GPU result exactly.
        dispatchAndVerify(device, input, outputWords, expected, WARMUP_ITERATIONS, null);

        DeviceMetrics before = device.metrics();
        List<Long> latencies = new ArrayList<>(iterations);
        long gpuResults = dispatchAndVerify(device, input, outputWords, expected, iterations, latencies);
        DeviceMetrics after = device.metrics();
        assertTrue(gpuResults > 0, "No GPU result was produced for workload " + workload.label());

        return new WorkloadReport(
            workload.label(),
            workload.interpolators(),
            workload.cellWidth(),
            workload.cellHeight(),
            workload.cellsY(),
            input[1],
            input.length * Integer.BYTES,
            outputWords * Integer.BYTES,
            iterations,
            gpuResults,
            iterations - gpuResults,
            cpuWireReferenceNanos,
            percentiles(latencies),
            before,
            after,
            delta(before, after)
        );
    }

    /** Submits at most four requests before waiting, so the configured native batcher can coalesce work. */
    private static long dispatchAndVerify(
        VulkanDevice device,
        int[] input,
        int outputWords,
        int[] expected,
        int requestCount,
        List<Long> timedLatencies
    ) throws Exception {
        long gpuResults = 0;
        for (int first = 0; first < requestCount; first += MAX_PARALLEL_REQUESTS) {
            int waveSize = Math.min(MAX_PARALLEL_REQUESTS, requestCount - first);
            List<PendingRequest> pending = new ArrayList<>(waveSize);
            for (int i = 0; i < waveSize; i++) {
                long startedAt = System.nanoTime();
                CompletableFuture<int[]> future = device.computeAsync(input, outputWords, input[1]);
                CompletableFuture<Long> completedAt = new CompletableFuture<>();
                future.whenComplete((ignored, failure) -> completedAt.complete(System.nanoTime()));
                pending.add(new PendingRequest(startedAt, future, completedAt));
            }
            for (PendingRequest request : pending) {
                int[] actual = request.future().get(30, TimeUnit.SECONDS);
                long completedAt = request.completedAtNanos().get(30, TimeUnit.SECONDS);
                if (timedLatencies != null) timedLatencies.add(completedAt - request.startedAtNanos());
                if (actual == null) continue; // A null dispatch is an allowed CPU-fallback outcome.
                assertTrue(ExactCompute.equal(expected, actual, VanillaTerrainInterpolation.WORKLOAD),
                    "Vulkan workload-4 result differed from the exact CPU wire reference");
                gpuResults++;
            }
        }
        return gpuResults;
    }

    private static int[] createInput(Workload workload) {
        int zBoundaries = 16 / workload.cellWidth() + 1;
        int yBoundaries = workload.cellsY() + 1;
        double[][][] slice0 = new double[workload.interpolators()][zBoundaries][yBoundaries];
        double[][][] slice1 = new double[workload.interpolators()][zBoundaries][yBoundaries];
        SplittableRandom random = new SplittableRandom(DENSITY_SEED ^ workload.invocationCount());
        for (int interpolator = 0; interpolator < workload.interpolators(); interpolator++) {
            for (int z = 0; z < zBoundaries; z++) {
                for (int y = 0; y < yBoundaries; y++) {
                    slice0[interpolator][z][y] = random.nextDouble(-1_000_000.0, 1_000_000.0);
                    slice1[interpolator][z][y] = random.nextDouble(-1_000_000.0, 1_000_000.0);
                }
            }
        }
        return VanillaTerrainInterpolation.input(workload.cellWidth(), workload.cellHeight(), workload.cellsY(), slice0, slice1);
    }

    private static List<Workload> workloadMatrix() {
        return List.of(
            new Workload("1K", 1, 1, 1, 32, 1_024),
            new Workload("4K", 1, 1, 1, 128, 4_096),
            new Workload("16K", 1, 4, 1, 128, 16_384),
            new Workload("64K", 1, 8, 1, 256, 65_536),
            new Workload("256K", 1, 16, 1, 512, 262_144),
            new Workload("1M", 4, 16, 1, 512, 1_048_576)
        );
    }

    private static Percentiles percentiles(List<Long> samples) {
        if (samples.isEmpty()) throw new AssertionError("No API latency samples were recorded");
        List<Long> sorted = samples.stream().sorted().toList();
        int count = sorted.size();
        long median = count % 2 == 1
            ? sorted.get(count / 2)
            : sorted.get(count / 2 - 1) + (sorted.get(count / 2) - sorted.get(count / 2 - 1)) / 2;
        return new Percentiles(median, percentile(sorted, 0.95), percentile(sorted, 0.99));
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int)Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static DeviceMetrics delta(DeviceMetrics before, DeviceMetrics after) {
        return new DeviceMetrics(
            after.submittedJobs() - before.submittedJobs(),
            after.completedJobs() - before.completedJobs(),
            after.rejectedJobs() - before.rejectedJobs(),
            after.timedOutJobs() - before.timedOutJobs(),
            after.failedJobs() - before.failedJobs(),
            after.batchesSubmitted() - before.batchesSubmitted(),
            after.inputBytes() - before.inputBytes(),
            after.outputBytes() - before.outputBytes(),
            after.submitCpuNanos() - before.submitCpuNanos(),
            after.hostWriteNanos() - before.hostWriteNanos(),
            after.readbackNanos() - before.readbackNanos(),
            after.queuedNanos() - before.queuedNanos(),
            after.gpuSamples() - before.gpuSamples(),
            after.gpuNanos() - before.gpuNanos(),
            after.lastGpuNanos(),
            after.fallbackFenceNanos() - before.fallbackFenceNanos(),
            after.allocations() - before.allocations(),
            after.residentBytes(),
            after.queueDepth(),
            after.inFlightBatches(),
            after.available()
        );
    }

    private static String benchmarkConfig(String uuid) {
        return """
            gpu:
              multi-gpu:
                enabled: false
                devices:
                  - "%s"
              force: true
              timeout-ms: 2000
              cache:
                enabled: false
              scheduler:
                async-submit: true
                batch-max-jobs: 4
                batch-window-us: 200
                queue-capacity: 32
                device-local-threshold-bytes: 65536
                buffer-budget-mib: 128
                timestamps: true
              verification:
                full-first-batches: 16
                sample-one-in: 128
            chunk-generation:
              gpu-acceleration:
                enabled: true
                terrain-enabled: true
                execution-contexts: 4
                gpu-usage-fallback: 100
              vanilla-terrain:
                enabled: true
                mode: strict
                max-interpolators: 16
                max-slab-values: 1048576
                minimum-values: 1
            """.formatted(uuid);
    }

    private static Path findModuleRoot() throws Exception {
        Path codeLocation = Path.of(GPurConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath().normalize();
        Path current = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("build.gradle.kts"))
                && Files.isRegularFile(current.resolve("src/main/java/org/gpur/GPurConfig.java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the gpur-server module root from " + codeLocation);
    }

    private record Workload(String label, int interpolators, int cellWidth, int cellHeight, int cellsY, int invocationCount) {}
    private record PendingRequest(long startedAtNanos, CompletableFuture<int[]> future,
                                  CompletableFuture<Long> completedAtNanos) {}
    private record Percentiles(long medianNanos, long p95Nanos, long p99Nanos) {}
    private record WorkloadReport(String label, int interpolators, int cellWidth, int cellHeight, int cellsY,
                                  int invocations, int inputBytes, int outputBytes, int measuredRequests,
                                  long gpuResults, long cpuFallbacks, long cpuWireReferenceNanos,
                                  Percentiles apiEndToEndNanos, DeviceMetrics metricsBefore,
                                  DeviceMetrics metricsAfter, DeviceMetrics metricsDelta) {}
    private record BenchmarkReport(String timestampUtc, String deviceUuid, String deviceName, String apiVersion,
                                   String scope, int warmupRequestsPerWorkload, int measuredRequestsPerWorkload,
                                   int maximumParallelRequests, List<WorkloadReport> workloads) {}

    private static final class ConfigSnapshot {
        private final List<SavedField> fields;

        private ConfigSnapshot(List<SavedField> fields) {
            this.fields = fields;
        }

        private static ConfigSnapshot capture() throws IllegalAccessException {
            List<SavedField> saved = new ArrayList<>();
            for (Field field : GPurConfig.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof List<?> list) value = List.copyOf(list);
                saved.add(new SavedField(field, value));
            }
            return new ConfigSnapshot(List.copyOf(saved));
        }

        private void restore() throws IllegalAccessException {
            for (SavedField saved : this.fields) saved.field().set(null, saved.value());
        }
    }

    private record SavedField(Field field, Object value) {}
}
