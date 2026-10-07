package org.gpur.compute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.gpur.GPurConfig;

/** Immutable CPU snapshots in, immutable results out. Each physical UUID is opened once. */
public final class ComputeService implements AutoCloseable {
    private final List<DeviceState> devices;
    private final AtomicLong gpuResults = new AtomicLong();
    private final AtomicLong cpuFallbacks = new AtomicLong();
    private final AtomicLongArray eligibilitySkips = new AtomicLongArray(3);
    private final AtomicLong selection = new AtomicLong();
    private final ComputeResultGate resultGate = new ComputeResultGate();
    private final Logger logger;

    public ComputeService(Logger logger) {
        this.logger = logger;
        List<VulkanDevice> opened = new ArrayList<>();
        if (GPurConfig.gpuAccelerationEnabled) {
            try {
                List<VulkanDevice.Info> inventory = VulkanDevice.discover();
                for (String configured : GPurConfig.gpuDevices) {
                    if (!configured.equalsIgnoreCase("auto") && inventory.stream().noneMatch(i -> matches(configured, i))) {
                        logger.warning("GPU selector matched no device: " + configured);
                    }
                }
                for (VulkanDevice.Info info : inventory) {
                    logger.info("GPU " + info.name() + " uuid=" + info.uuid() + " FP64=" + info.float64());
                    if (!info.float64() || GPurConfig.gpuDevices.stream().noneMatch(selector -> matches(selector, info))) continue;
                    VulkanDevice device = null;
                    try {
                        device = VulkanDevice.create(info.uuid());
                        selfTest(device);
                        opened.add(device);
                        logger.info("Exact compute ready: " + info.name());
                        if (!GPurConfig.multiGpuEnabled) break;
                    } catch (RuntimeException | LinkageError failure) {
                        if (device != null) device.close();
                        logger.warning("GPU rejected: " + info.name() + ": " + failure.getMessage());
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                logger.warning("Vulkan unavailable, using CPU: " + failure.getMessage());
            }
        }
        this.devices = opened.stream().map(DeviceState::new).toList();
    }

    private static boolean matches(String selector, VulkanDevice.Info info) {
        return selector.equalsIgnoreCase("auto") || selector.equalsIgnoreCase(info.uuid()) || selector.equalsIgnoreCase(info.name());
    }

    public boolean eligible(int workload) {
        if (workload < 1 || workload > 2) return false;
        if (this.resultGate.allows(workload)) {
            long now = System.nanoTime();
            for (DeviceState state : this.devices) {
                if (state.canAccept(workload, now, GPurConfig.gpuForce)) return true;
            }
        }
        // Callers check eligibility before building a snapshot, so tryCompute's fallback
        // counter cannot account for these skips. Keep this separate from submitted work.
        this.eligibilitySkips.incrementAndGet(workload);
        return false;
    }

    /** Returns null without mutation when the caller should run its original CPU implementation. */
    public int[] tryCompute(int[] input) {
        ExactCompute.validate(input);
        if (input[1] == 0) return new int[0];
        int workload = input[0];
        if (!this.eligible(workload)) {
            this.cpuFallbacks.incrementAndGet();
            return null;
        }
        List<DeviceState> candidates = new ArrayList<>(this.devices.size());
        int first = (int)Math.floorMod(this.selection.getAndIncrement(), (long)this.devices.size());
        long now = System.nanoTime();
        for (int i = 0; i < this.devices.size(); i++) {
            DeviceState state = this.devices.get((first + i) % this.devices.size());
            if (state.canAccept(workload, now, GPurConfig.gpuForce)) candidates.add(state);
        }
        // List.sort is stable: rotate equal-busy devices while preferring free capacity.
        candidates.sort(Comparator.comparingInt(state -> state.device.busy()));
        for (DeviceState state : candidates) {
            if (this.resultGate.isClosed()) continue;
            VulkanDevice device = state.device;
            WorkloadState metrics = state.workloads[workload];
            DispatchBackoff.Ticket backoffTicket = metrics.backoff.admit(System.nanoTime(), GPurConfig.gpuForce);
            if (backoffTicket == null || !device.canAccept(workload)) continue;
            try {
                long start = System.nanoTime();
                int[] result = device.compute(input, input[1] * (workload == 1 ? 2 : 1), input[1]);
                long gpuNanos = System.nanoTime() - start;
                if (result == null) continue;
                long attempt = metrics.completedDispatches.incrementAndGet();
                metrics.dispatchNanos.addAndGet(gpuNanos);
                metrics.maxDispatchNanos.accumulateAndGet(gpuNanos, Math::max);
                // Each device/workload verifies actual snapshots independently. The timed
                // CPU reference decodes the wire snapshot; it is not the server's original
                // CPU query and excludes caller preparation/reduction. This conservative
                // dispatch check is a backoff signal, not evidence of end-to-end speedup.
                if (metrics.backoff.shouldSample(attempt, backoffTicket)) {
                    start = System.nanoTime();
                    int[] reference = ExactCompute.reference(input);
                    long cpuNanos = System.nanoTime() - start;
                    metrics.referenceSamples.incrementAndGet();
                    metrics.referenceNanos.addAndGet(cpuNanos);
                    if (!ExactCompute.equal(reference, result, workload)) {
                        this.resultGate.disable(device::disable);
                        this.logger.warning("GPU parity failed, device disabled: " + device.name());
                        this.cpuFallbacks.incrementAndGet();
                        return null;
                    }
                    if (metrics.backoff.record(backoffTicket, gpuNanos, cpuNanos, GPurConfig.gpuForce, System.nanoTime())) metrics.backoffs.incrementAndGet();
                }
                if (!this.resultGate.accept(workload, device::available, () -> {
                    metrics.gpuResults.incrementAndGet();
                    this.gpuResults.incrementAndGet();
                })) {
                    this.cpuFallbacks.incrementAndGet();
                    return null;
                }
                return result;
            } catch (RuntimeException failure) {
                this.resultGate.disable(device::disable);
                this.logger.warning("GPU disabled, using CPU: " + device.name() + ": " + failure.getMessage());
            }
        }
        this.cpuFallbacks.incrementAndGet();
        return null;
    }

    public String status() {
        return "devices=" + this.devices.stream().map(state -> state.device.name() + "[" + (state.device.available() ? "ready" : "disabled")
            + ", busy=" + state.device.busy() + "]").toList() + ", completed GPU results=" + this.gpuResults.get()
            + ", CPU fallbacks=" + this.cpuFallbacks.get() + ", Anti-Xray rejected=" + this.resultGate.isAntiXrayRejected()
            + ", eligibility skips=[distance=" + this.eligibilitySkips.get(1) + ", Anti-Xray=" + this.eligibilitySkips.get(2)
            + "], device workloads=" + this.deviceWorkloads().stream().map(d -> d.name() + "[workload=" + d.workload()
                + ", results=" + d.gpuResults() + ", dispatch avg/max ns=" + d.averageDispatchNanos() + "/" + d.maxDispatchNanos()
                + ", wire CPU avg ns=" + d.averageReferenceNanos() + ", samples=" + d.referenceSamples()
                + ", backoffs=" + d.backoffs() + ", backoff ms=" + d.backoffMillis() + "]").toList();
    }

    public int activeDeviceCount() {
        if (this.resultGate.isClosed()) return 0;
        return (int)this.devices.stream().filter(state -> state.device.available()).count();
    }

    /** Read-only counters since service initialization/reload. Taking a snapshot never submits work. */
    public record StatusSnapshot(boolean stopped, boolean antiXrayRejected, long gpuResults, long cpuFallbacks,
                                 long distanceSkips, long antiXraySkips, List<DeviceWorkloadStatus> workloads) {
        public StatusSnapshot {
            workloads = List.copyOf(workloads);
        }
    }

    public StatusSnapshot statusSnapshot() {
        return new StatusSnapshot(this.resultGate.isClosed(), this.resultGate.isAntiXrayRejected(),
            this.gpuResults.get(), this.cpuFallbacks.get(), this.eligibilitySkips.get(1),
            this.eligibilitySkips.get(2), this.deviceWorkloads());
    }

    /** Read-only diagnostics. Dispatch timings include upload, fence wait, and readback,
     * but exclude caller snapshot preparation, result application, and sampled parity work. */
    public record DeviceWorkloadStatus(String uuid, String name, int workload, boolean available, int busy,
                                       long gpuResults, long averageDispatchNanos, long maxDispatchNanos,
                                       long referenceSamples, long averageReferenceNanos,
                                       long backoffs, long backoffMillis) {}

    public List<DeviceWorkloadStatus> deviceWorkloads() {
        long now = System.nanoTime();
        List<DeviceWorkloadStatus> result = new ArrayList<>(this.devices.size() * 2);
        for (DeviceState state : this.devices) {
            for (int workload = 1; workload <= 2; workload++) {
                WorkloadState metrics = state.workloads[workload];
                long dispatches = metrics.completedDispatches.get();
                long samples = metrics.referenceSamples.get();
                result.add(new DeviceWorkloadStatus(state.device.uuid(), state.device.name(), workload,
                    !this.resultGate.isClosed() && state.device.available(), state.device.busy(), metrics.gpuResults.get(),
                    dispatches == 0 ? 0 : metrics.dispatchNanos.get() / dispatches, metrics.maxDispatchNanos.get(),
                    samples, samples == 0 ? 0 : metrics.referenceNanos.get() / samples, metrics.backoffs.get(),
                    TimeUnit.NANOSECONDS.toMillis(metrics.backoff.retryAfter() == 0 ? 0 : Math.max(0, metrics.backoff.retryAfter() - now))));
            }
        }
        return List.copyOf(result);
    }

    public void rejectAntiXray() {
        if (this.resultGate.rejectAntiXray()) this.logger.warning("Anti-Xray packet parity failed; retaining Paper CPU obfuscation");
    }

    public static void selfTest(VulkanDevice device) {
        Random random = new Random(0x47507572);
        double[] positions = new double[900];
        for (int i = 0; i < positions.length; i++) positions[i] = (random.nextDouble() - 0.5) * 60_000_000;
        positions[0] = 29_999_976.01;
        positions[1] = 0;
        positions[2] = 0;
        check(device, ExactCompute.distances(30_000_000, 0, 0, positions));
        check(device, ExactCompute.distances(-30_000_000, -64, 0, positions));
        double tiny = Double.MIN_NORMAL;
        check(device, ExactCompute.distances(0, 0, 0, new double[]{tiny, tiny, tiny, 1e-154, 1e-155, 1e-156,
            Double.NaN, 0, 0, Double.POSITIVE_INFINITY, 0, 0, -0.0, 0.0, -0.0}));
        int[] section = new int[2 + ExactCompute.SECTION_WORDS * 2];
        section[0] = 2;
        section[1] = 8192;
        for (int i = 2; i < section.length; i++) section[i] = random.nextInt(4);
        check(device, section);
    }

    private static void check(VulkanDevice device, int[] input) {
        int[] actual = device.compute(input, input[1] * (input[0] == 1 ? 2 : 1), input[1]);
        if (!ExactCompute.equal(ExactCompute.reference(input), actual, input[0])) {
            throw new IllegalStateException("CPU/GPU parity self-test failed");
        }
    }

    @Override
    public void close() {
        this.resultGate.close();
        for (DeviceState state : this.devices) state.device.close();
    }

    private static final class DeviceState {
        private final VulkanDevice device;
        private final WorkloadState[] workloads = {null, new WorkloadState(), new WorkloadState()};

        private DeviceState(VulkanDevice device) {
            this.device = device;
        }

        private boolean canAccept(int workload, long now, boolean force) {
            return this.workloads[workload].backoff.admit(now, force) != null
                && this.device.canAccept(workload);
        }
    }

    private static final class WorkloadState {
        private final DispatchBackoff backoff = new DispatchBackoff();
        private final AtomicLong completedDispatches = new AtomicLong();
        private final AtomicLong gpuResults = new AtomicLong();
        private final AtomicLong dispatchNanos = new AtomicLong();
        private final AtomicLong maxDispatchNanos = new AtomicLong();
        private final AtomicLong referenceSamples = new AtomicLong();
        private final AtomicLong referenceNanos = new AtomicLong();
        private final AtomicLong backoffs = new AtomicLong();
    }
}
