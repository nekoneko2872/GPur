package org.gpur.compute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.gpur.GPurConfig;

/** Immutable CPU snapshots in, immutable results out. Each physical UUID is opened once. */
public final class ComputeService implements AutoCloseable {
    private final List<DeviceState> devices;
    private final AtomicLong gpuResults = new AtomicLong();
    private final AtomicLong cpuFallbacks = new AtomicLong();
    private final AtomicLongArray eligibilitySkips = new AtomicLongArray(3);
    private final AtomicLong selection = new AtomicLong();
    private final AtomicLong vanillaFallbacks = new AtomicLong();
    private final AtomicLong vanillaParityFailures = new AtomicLong();
    private final AtomicLong vanillaResultEpoch = new AtomicLong();
    private final AtomicLong vanillaSnapshotBytes = new AtomicLong();
    private final AtomicLongArray worldgenFallbacks = new AtomicLongArray(7);
    private final java.util.Set<WorldgenResult> worldgenFrames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<WorldgenResult>> pendingWorldgen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<int[]>> pendingVanilla = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<?>> pendingGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object generationLifecycle = new Object();
    private final CompletableFuture<Void> retirementCompletion = new CompletableFuture<>();
    private int continuationScopes;
    private boolean generationsClosed;
    private boolean retiring;
    private boolean devicesClosed;
    private final long vanillaSnapshotByteLimit = (long)GPurConfig.gpuBufferBudgetMiB * 1024 * 1024;
    private final boolean vanillaEnabled = GPurConfig.vanillaTerrainEnabled && GPurConfig.gpuAccelerationEnabled && GPurConfig.terrainGpuEnabled;
    private final boolean vanillaVerifyEveryBatch = GPurConfig.vanillaTerrainVerifyEveryBatch;
    private final VanillaVerificationPolicy.Mode vanillaMode = GPurConfig.vanillaTerrainMode;
    private final boolean vanillaAsync = GPurConfig.gpuAsyncSubmit;
    private final boolean noiseEnabled = GPurConfig.vanillaNoiseGpuEnabled;
    private final boolean aquiferEnabled = GPurConfig.vanillaAquiferGpuEnabled;
    private final int vanillaMaxInterpolators = GPurConfig.vanillaTerrainMaxInterpolators;
    private final int vanillaMaxValues = GPurConfig.vanillaTerrainMaxSlabValues;
    private final int vanillaMinimumValues = GPurConfig.vanillaTerrainMinValues;
    private final ComputeResultGate resultGate = new ComputeResultGate();
    private final Logger logger;

    public ComputeService(Logger logger) {
        this.logger = logger;
        List<DeviceState> opened = new ArrayList<>();
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
                        if (this.vanillaEnabled) device.prepareWorldgenPipelines(this.noiseEnabled, this.aquiferEnabled);
                        selfTest(device);
                        if (this.vanillaEnabled) {
                            for (int[] corpus : ExactStartupCorpus.workloads()) check(device, corpus);
                            for (int[] corpus : WorldgenStartupCorpus.workloads(this.noiseEnabled, this.aquiferEnabled)) check(device, corpus);
                        }
                        // The former custom terrain generator is retired. Do not calibrate its kernel.
                        int terrainBatchSize = 1;
                        if (!device.available()) throw new IllegalStateException("GPU failed terrain calibration validation");
                        opened.add(new DeviceState(device, terrainBatchSize));
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
        this.devices = List.copyOf(opened);
    }

    /** CPU-only tests provide a simulated backend; production always performs discovery and self-tests. */
    ComputeService(Logger logger, List<VulkanDevice> backends) {
        this.logger = logger;
        this.devices = backends.stream().map(device -> new DeviceState(device, 1)).toList();
    }

    private static boolean matches(String selector, VulkanDevice.Info info) {
        return selector.equalsIgnoreCase("auto") || selector.equalsIgnoreCase(info.uuid()) || selector.equalsIgnoreCase(info.name());
    }

    /**
     * Keeps the enclosing chunk operation cancellable even if its CPU continuation was accepted
     * and subsequently discarded by a halted executor. The owner installs its cancellation
     * cleanup before registration; cancellation must serialize cleanup with block mutation.
     */
    public <T> CompletableFuture<T> trackVanillaGeneration(CompletableFuture<T> result) {
        java.util.Objects.requireNonNull(result, "result");
        boolean tracked;
        synchronized (this.generationLifecycle) {
            tracked = !this.generationsClosed && (!this.retiring || this.continuationScopes > 0);
            if (tracked) this.pendingGenerations.add(result);
        }
        result.whenComplete((value, failure) -> {
            synchronized (this.generationLifecycle) {
                this.pendingGenerations.remove(result);
                this.completeRetirementIfDrained();
            }
        });
        if (!tracked) result.cancel(false);
        return result;
    }

    /** Pins a generator setup until it has registered its asynchronous owner. */
    public boolean tryAcquireVanillaContinuation() {
        synchronized (this.generationLifecycle) {
            if (this.generationsClosed || this.retiring) return false;
            ++this.continuationScopes;
            return true;
        }
    }

    public void releaseVanillaContinuation() {
        synchronized (this.generationLifecycle) {
            if (this.continuationScopes <= 0) throw new IllegalStateException("Unbalanced vanilla continuation");
            --this.continuationScopes;
            this.completeRetirementIfDrained();
        }
    }

    public CompletableFuture<Void> retirementCompletion() { return this.retirementCompletion; }

    private void completeRetirementIfDrained() {
        if (this.retiring && this.devicesClosed && this.continuationScopes == 0 && this.pendingGenerations.isEmpty()) {
            this.retirementCompletion.complete(null);
        }
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
        if (input[0] >= 3) throw new IllegalArgumentException("Terrain jobs require their dedicated bounded compute path");
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

    /** Dedicated terrain workers use the same logical device and context pool as other kernels.
     * Terrain has no speed cooldown: it is admitted for additional throughput, with exact fallback. */
    public int[] tryTerrainCompute(int deviceIndex, int[] input) {
        ExactCompute.validate(input);
        if (input[0] != 3) throw new IllegalArgumentException("Not a terrain workload");
        if (deviceIndex < 0 || deviceIndex >= this.devices.size()) throw new IllegalArgumentException("Unknown GPU index");
        for (int offset = 0; offset < this.devices.size(); offset++) {
            DeviceState state = this.devices.get((deviceIndex + offset) % this.devices.size());
            int[] result = this.tryTerrainOnDevice(state, input);
            if (result != null) return result;
        }
        return null;
    }

    private int[] tryTerrainOnDevice(DeviceState state, int[] input) {
        VulkanDevice device = state.device;
        if (!this.resultGate.allows(3) || !device.canAccept(3)) return null;
        try {
            long start = System.nanoTime();
            int[] result = device.compute(input, ExactCompute.outputWords(input), input[1]);
            long elapsed = System.nanoTime() - start;
            if (result == null) return null;
            long attempt = state.terrainDispatches.incrementAndGet();
            state.terrainDispatchNanos.addAndGet(elapsed);
            state.terrainMaxDispatchNanos.accumulateAndGet(elapsed, Math::max);
            if (attempt == 1 || attempt % this.terrainParityInterval == 0) {
                state.terrainParitySamples.incrementAndGet();
                if (!ExactCompute.equal(ExactCompute.reference(input), result, 3)) {
                    this.resultGate.disable(device::disable);
                    this.logger.warning("Terrain parity failed, device disabled: " + device.name());
                    return null;
                }
            }
            if (!this.resultGate.accept(3, device::available, () -> {
                state.terrainBatches.incrementAndGet();
                state.terrainChunks.addAndGet(input[1] / 256);
            })) return null;
            return result;
        } catch (RuntimeException failure) {
            this.resultGate.disable(device::disable);
            this.logger.warning("Terrain GPU disabled, using identical CPU terrain: " + device.name() + ": " + failure.getMessage());
            return null;
        }
    }

    private final int terrainParityInterval = GPurConfig.terrainParityInterval;

    /** Admission is independent of player count and never installs a different world generator. */
    public boolean vanillaTerrainEligible() {
        return this.vanillaEnabled && this.activeDeviceCount() > 0;
    }

    public boolean vanillaTerrainAsyncEligible() {
        return this.vanillaAsync && this.vanillaTerrainEligible();
    }

    public boolean worldgenEligible(int workload) {
        return this.vanillaTerrainAsyncEligible() && switch (workload) {
            case VanillaNoiseBatch.WORKLOAD -> this.noiseEnabled;
            case VanillaAquiferBatch.WORKLOAD -> this.aquiferEnabled;
            default -> false;
        };
    }

    public CompletableFuture<WorldgenResult> prepareWorldgenAsync(int[] input, Executor continuation) {
        return this.prepareWorldgenAsync(input, continuation, 0L);
    }

    /** The optional owner charge includes lookup tables retained alongside the numeric frame. */
    public CompletableFuture<WorldgenResult> prepareWorldgenAsync(int[] input, Executor continuation, long ownerBytes) {
        ExactCompute.validate(input);
        int workload = input[0];
        if (workload != VanillaNoiseBatch.WORKLOAD && workload != VanillaAquiferBatch.WORKLOAD) {
            throw new IllegalArgumentException("Not a noise or aquifer workload");
        }
        java.util.Objects.requireNonNull(continuation, "continuation");
        if (ownerBytes < 0) throw new IllegalArgumentException("Negative owner byte reservation");
        if (!this.worldgenEligible(workload) || input[1] < this.vanillaMinimumValues || input[1] > this.vanillaMaxValues) {
            this.worldgenFallbacks.incrementAndGet(workload);
            return CompletableFuture.completedFuture(null);
        }
        int outputWords = ExactCompute.outputWords(input);
        long retained;
        try { retained = Math.addExact(ownerBytes, ((long)input.length + outputWords) * Integer.BYTES); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Worldgen owner byte reservation overflow", overflow); }
        if (!VulkanMemoryPolicy.tryReserve(this.vanillaSnapshotBytes, this.vanillaSnapshotByteLimit, retained)) {
            this.worldgenFallbacks.incrementAndGet(workload);
            return CompletableFuture.completedFuture(null);
        }
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<WorldgenResult> owned = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                this.vanillaSnapshotBytes.addAndGet(-retained);
            }
            WorldgenResult frame = owned.get();
            if (frame != null) this.worldgenFrames.remove(frame);
        };
        final int[] snapshot;
        try { snapshot = input.clone(); }
        catch (RuntimeException | Error failedCopy) { release.run(); throw failedCopy; }
        DeviceState selected = this.selectWorldgenDevice(workload, input[1]);
        if (selected == null) {
            release.run();
            this.worldgenFallbacks.incrementAndGet(workload);
            return CompletableFuture.completedFuture(null);
        }
        WorldgenState metrics = selected.worldgen[workload];
        metrics.load.reserve(snapshot[1]);
        long started = System.nanoTime();
        long epoch = this.vanillaResultEpoch.get();
        final CompletableFuture<int[]> raw;
        try { raw = selected.device.computeAsync(snapshot, outputWords, snapshot[1]); }
        catch (RuntimeException | Error failure) {
            metrics.load.complete(snapshot[1], 0, false);
            release.run();
            throw failure;
        }
        CompletableFuture<WorldgenResult> result = new CompletableFuture<>();
        raw.whenComplete((words, failure) -> metrics.load.complete(snapshot[1], System.nanoTime() - started, words != null));
        if (!this.resultGate.accept(workload, selected.device::available, () -> this.pendingWorldgen.add(result))) {
            raw.cancel(false);
            release.run();
            return CompletableFuture.completedFuture(null);
        }
        result.whenComplete((frame, failure) -> {
            this.pendingWorldgen.remove(result);
            if (frame == null || failure != null) { raw.cancel(false); release.run(); }
        });
        try {
            raw.handleAsync((words, failure) -> {
                try {
                    if (failure != null || !this.acceptWorldgenResult(selected, metrics, snapshot, words, epoch, System.nanoTime() - started)) {
                        this.worldgenFallbacks.incrementAndGet(workload);
                        release.run();
                        result.complete(null);
                        return null;
                    }
                    WorldgenResult frame = new WorldgenResult(words,
                        () -> this.resultGate.allows(workload) && selected.device.available() && this.vanillaResultEpoch.get() == epoch,
                        release, metrics.consumedValues::addAndGet);
                    owned.set(frame);
                    if (!this.resultGate.accept(workload, selected.device::available, () -> this.worldgenFrames.add(frame))
                        || released.get() || !result.complete(frame)) frame.close();
                } catch (RuntimeException | Error failedVerification) {
                    release.run();
                    result.completeExceptionally(failedVerification);
                }
                return null;
            }, continuation).whenComplete((ignored, failure) -> {
                if (failure != null) { release.run(); result.completeExceptionally(failure); }
            });
        } catch (RuntimeException | Error rejectedContinuation) {
            raw.cancel(false);
            release.run();
            result.completeExceptionally(rejectedContinuation);
        }
        return result;
    }

    private DeviceState selectWorldgenDevice(int workload, int values) {
        if (!this.resultGate.allows(workload) || this.devices.isEmpty()) return null;
        int first = (int)Math.floorMod(this.selection.getAndIncrement(), (long)this.devices.size());
        DeviceState selected = null;
        double earliest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.devices.size(); i++) {
            DeviceState candidate = this.devices.get((first + i) % this.devices.size());
            if (!candidate.device.available()) continue;
            double estimate = candidate.worldgen[workload].load.predictedNanos(values) * (1.0 + candidate.device.busy());
            if (estimate < earliest) { earliest = estimate; selected = candidate; }
        }
        return selected;
    }

    private boolean acceptWorldgenResult(DeviceState device, WorldgenState metrics, int[] input, int[] words, long epoch, long elapsed) {
        int workload = input[0];
        if (words == null || !this.resultGate.allows(workload) || !device.device.available() || this.vanillaResultEpoch.get() != epoch) return false;
        metrics.dispatches.incrementAndGet();
        metrics.dispatchNanos.addAndGet(elapsed);
        if (words.length != ExactCompute.outputWords(input)) {
            this.quarantineVanilla(device);
            return false;
        }
        if (metrics.verification.nextVerificationRequired()) {
            metrics.paritySamples.incrementAndGet();
            if (!ExactCompute.equal(ExactCompute.reference(input), words, workload)) {
                this.quarantineVanilla(device);
                return false;
            }
            metrics.verification.recordVerificationSuccess();
        }
        if (!metrics.verification.adoptsResults()) return false;
        return this.resultGate.accept(workload, device.device::available, () -> {
            metrics.accepted.incrementAndGet();
            metrics.values.addAndGet(input[1]);
        });
    }

    public record WorldgenDeviceStatus(String uuid, String name, int workload, long dispatches,
                                      long acceptedBatches, long computedValues, long consumedValues,
                                      long paritySamples, long averageDispatchNanos) {}

    public List<WorldgenDeviceStatus> worldgenDevices() {
        List<WorldgenDeviceStatus> status = new ArrayList<>(this.devices.size() * 2);
        for (DeviceState state : this.devices) {
            for (int workload = 5; workload <= 6; workload++) {
                WorldgenState metrics = state.worldgen[workload];
                long dispatches = metrics.dispatches.get();
                status.add(new WorldgenDeviceStatus(state.device.uuid(), state.device.name(), workload, dispatches,
                    metrics.accepted.get(), metrics.values.get(), metrics.consumedValues.get(), metrics.paritySamples.get(),
                    dispatches == 0 ? 0 : metrics.dispatchNanos.get() / dispatches));
            }
        }
        return List.copyOf(status);
    }

    public long worldgenFallbacks(int workload) {
        if (workload != 5 && workload != 6) throw new IllegalArgumentException("Unknown worldgen workload");
        return this.worldgenFallbacks.get(workload);
    }

    /** Called after vanilla has sampled both density slices, before it starts the current cell slab. */
    public VanillaTerrainSlab prepareVanillaSlab(int width, int height, int cellsY, double[][][] slice0, double[][][] slice1) {
        if (!this.vanillaTerrainEligible()) return null;
        long epoch = this.vanillaResultEpoch.get();
        long values = 2L * slice0.length * width * height * cellsY * 16;
        if (slice0.length > this.vanillaMaxInterpolators || values < this.vanillaMinimumValues || values > this.vanillaMaxValues) {
            this.vanillaFallbacks.incrementAndGet();
            return null;
        }
        final int[] input;
        try {
            input = VanillaTerrainInterpolation.input(width, height, cellsY, slice0, slice1);
        } catch (IllegalArgumentException failure) {
            // Unusual datapack dimensions/density values retain their original CPU evaluation.
            this.vanillaFallbacks.incrementAndGet();
            return null;
        }
        int[] result = this.tryVanillaCompute(input);
        return result == null ? null : new VanillaTerrainSlab(result, width, height, cellsY,
            () -> this.resultGate.allows(4) && this.vanillaResultEpoch.get() == epoch);
    }

    public CompletableFuture<VanillaTerrainSlab> prepareVanillaSlabAsync(
        int width, int height, int cellsY, double[][][] slice0, double[][][] slice1, Executor continuation
    ) {
        if (!this.vanillaTerrainAsyncEligible()) return CompletableFuture.completedFuture(null);
        long epoch = this.vanillaResultEpoch.get();
        long values = 2L * slice0.length * width * height * cellsY * 16;
        if (slice0.length > this.vanillaMaxInterpolators || values < this.vanillaMinimumValues || values > this.vanillaMaxValues) {
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        final int[] input;
        try { input = VanillaTerrainInterpolation.input(width, height, cellsY, slice0, slice1); }
        catch (IllegalArgumentException rejected) {
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        // Native slots can complete before the CPU consumes their large readback arrays.
        // Hold a separate service-wide CPU budget until consumption, fallback, or cancellation.
        long retainedBytes = (long)input.length * Integer.BYTES + values * Double.BYTES;
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) this.vanillaSnapshotBytes.addAndGet(-retainedBytes);
        };
        if (!VulkanMemoryPolicy.tryReserve(this.vanillaSnapshotBytes, this.vanillaSnapshotByteLimit, retainedBytes)) {
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        try {
            CompletableFuture<VanillaTerrainSlab> prepared = this.tryVanillaComputeAsync(input, continuation).thenApply(result -> {
                if (result == null) { release.run(); return null; }
                return new VanillaTerrainSlab(result, width, height, cellsY,
                    () -> this.resultGate.allows(4) && this.vanillaResultEpoch.get() == epoch, release);
            });
            prepared.whenComplete((slab, failure) -> { if (failure != null) release.run(); });
            return prepared;
        } catch (RuntimeException | Error failure) {
            release.run();
            throw failure;
        }
    }

    /** Nonblocking numeric submission; verification runs on the supplied CPU continuation executor. */
    public CompletableFuture<int[]> tryVanillaComputeAsync(int[] input, Executor continuation) {
        ExactCompute.validate(input);
        return this.submitVanillaCompute(input.clone(), continuation);
    }

    private CompletableFuture<int[]> submitVanillaCompute(int[] input, Executor continuation) {
        ExactCompute.validate(input);
        if (input[0] != 4) throw new IllegalArgumentException("Not a vanilla interpolation workload");
        if (!this.vanillaTerrainEligible()) return CompletableFuture.completedFuture(null);
        if (input[5] > this.vanillaMaxInterpolators || input[1] < this.vanillaMinimumValues || input[1] > this.vanillaMaxValues) {
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        DeviceState selected = this.selectVanillaDevice(input[1]);
        if (selected == null) {
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        selected.load.reserve(input[1]);
        long started = System.nanoTime();
        final CompletableFuture<int[]> raw;
        try { raw = selected.device.computeAsync(input, ExactCompute.outputWords(input), input[1]); }
        catch (RuntimeException failure) {
            selected.load.complete(input[1], 0, false);
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        // Release scheduling accounting even if the continuation queue halts during shutdown.
        raw.whenComplete((result, failure) -> selected.load.complete(input[1], System.nanoTime() - started, result != null));
        CompletableFuture<int[]> result = new CompletableFuture<>();
        if (!this.resultGate.accept(4, selected.device::available, () -> this.pendingVanilla.add(result))) {
            raw.cancel(false);
            this.vanillaFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        result.whenComplete((accepted, failure) -> {
            this.pendingVanilla.remove(result);
            if (failure != null || accepted == null) raw.cancel(false);
        });
        try {
            raw.handleAsync((words, failure) -> {
                int[] accepted = failure == null ? this.acceptVanillaResult(selected, input, words, System.nanoTime() - started) : null;
                if (accepted == null && this.vanillaMode != VanillaVerificationPolicy.Mode.OBSERVE) this.vanillaFallbacks.incrementAndGet();
                return accepted;
            }, continuation).whenComplete((accepted, failure) -> {
                if (failure == null) result.complete(accepted);
                else result.completeExceptionally(failure);
            });
        } catch (RuntimeException | Error rejectedContinuation) {
            result.completeExceptionally(rejectedContinuation);
        }
        return result;
    }

    private DeviceState selectVanillaDevice(int values) {
        if (!this.resultGate.allows(4) || this.devices.isEmpty()) return null;
        int first = (int)Math.floorMod(this.selection.getAndIncrement(), (long)this.devices.size());
        DeviceState selected = null;
        double earliest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < this.devices.size(); i++) {
            DeviceState candidate = this.devices.get((first + i) % this.devices.size());
            if (!candidate.device.available()) continue;
            double estimate = candidate.load.predictedNanos(values);
            if (estimate < earliest) { earliest = estimate; selected = candidate; }
        }
        return selected;
    }

    private int[] acceptVanillaResult(DeviceState state, int[] input, int[] result, long elapsed) {
        if (result == null || !this.resultGate.allows(4) || !state.device.available()) return null;
        state.vanillaDispatches.incrementAndGet();
        state.vanillaDispatchNanos.addAndGet(elapsed);
        state.vanillaMaxDispatchNanos.accumulateAndGet(elapsed, Math::max);
        // Length checks are unconditional, including sampled mode and observe.
        if (result.length != ExactCompute.outputWords(input)) {
            this.quarantineVanilla(state);
            return null;
        }
        if (state.verification.nextVerificationRequired()) {
            long started = System.nanoTime();
            state.vanillaParitySamples.incrementAndGet();
            boolean matched = ExactCompute.equal(ExactCompute.reference(input), result, 4);
            state.vanillaVerificationNanos.addAndGet(System.nanoTime() - started);
            if (!matched) {
                this.quarantineVanilla(state);
                return null;
            }
            state.verification.recordVerificationSuccess();
        }
        if (!state.verification.adoptsResults()) {
            if (this.resultGate.accept(4, state.device::available, state.vanillaObservedSlabs::incrementAndGet)) return null;
            return null;
        }
        if (!this.resultGate.accept(4, state.device::available, () -> {
            state.vanillaSlabs.incrementAndGet();
            state.vanillaValues.addAndGet(input[1]);
        })) return null;
        return result;
    }

    private void quarantineVanilla(DeviceState state) {
        this.vanillaParityFailures.incrementAndGet();
        this.resultGate.disable(() -> {
            // Invalidate collected snapshots awaiting CPU installation, including other devices.
            this.vanillaResultEpoch.incrementAndGet();
            state.device.disable();
        });
        this.logger.warning("Vanilla worldgen parity failed; retaining original CPU terrain on " + state.device.name());
    }

    /** Exact FP64 interpolation only. Density graphs, aquifers, biome/ore/surface rules remain vanilla. */
    public int[] tryVanillaCompute(int[] input) {
        ExactCompute.validate(input);
        if (input[0] != 4) throw new IllegalArgumentException("Not a vanilla interpolation workload");
        if (!this.vanillaTerrainEligible()) return null;
        if (input[5] > this.vanillaMaxInterpolators || input[1] < this.vanillaMinimumValues || input[1] > this.vanillaMaxValues) {
            this.vanillaFallbacks.incrementAndGet();
            return null;
        }
        int first = (int)Math.floorMod(this.selection.getAndIncrement(), (long)this.devices.size());
        for (int offset = 0; offset < this.devices.size(); offset++) {
            DeviceState state = this.devices.get((first + offset) % this.devices.size());
            if (!this.resultGate.allows(4) || !state.device.canAccept(4)) continue;
            try {
                long started = System.nanoTime();
                int[] result = state.device.compute(input, ExactCompute.outputWords(input), input[1]);
                long elapsed = System.nanoTime() - started;
                if (result == null) continue;
                int[] accepted = this.acceptVanillaResult(state, input, result, elapsed);
                if (accepted != null) return accepted;
                if (this.vanillaMode == VanillaVerificationPolicy.Mode.OBSERVE) return null;
            } catch (RuntimeException failure) {
                this.resultGate.disable(state.device::disable);
                this.logger.warning("Vanilla terrain interpolation GPU disabled; retaining original CPU terrain: " + failure.getMessage());
            }
        }
        this.vanillaFallbacks.incrementAndGet();
        return null;
    }

    public record VanillaDeviceStatus(String uuid, long slabs, long values, long averageDispatchNanos,
                                      long maxDispatchNanos, long paritySamples, long observedSlabs,
                                      long verificationNanos, VulkanDevice.DeviceMetrics backend) {
        public VanillaDeviceStatus(String uuid, long slabs, long values, long averageDispatchNanos, long maxDispatchNanos, long paritySamples) {
            this(uuid, slabs, values, averageDispatchNanos, maxDispatchNanos, paritySamples, 0, 0, null);
        }
    }
    public record VanillaStatus(boolean enabled, boolean verifyEveryBatch, long cpuFallbacks, long parityFailures,
                                List<VanillaDeviceStatus> devices, String mode, boolean asyncSubmit) {
        public VanillaStatus { devices = List.copyOf(devices); }
        public VanillaStatus(boolean enabled, boolean verifyEveryBatch, long cpuFallbacks, long parityFailures, List<VanillaDeviceStatus> devices) {
            this(enabled, verifyEveryBatch, cpuFallbacks, parityFailures, devices, verifyEveryBatch ? "strict" : "verified-exact", false);
        }
    }

    public VanillaStatus vanillaTerrainStatus() {
        List<VanillaDeviceStatus> result = new ArrayList<>(this.devices.size());
        for (DeviceState state : this.devices) {
            long dispatches = state.vanillaDispatches.get();
            result.add(new VanillaDeviceStatus(state.device.uuid(), state.vanillaSlabs.get(), state.vanillaValues.get(),
                dispatches == 0 ? 0 : state.vanillaDispatchNanos.get() / dispatches,
                state.vanillaMaxDispatchNanos.get(), state.vanillaParitySamples.get(), state.vanillaObservedSlabs.get(),
                state.vanillaVerificationNanos.get(), state.device.metrics()));
        }
        return new VanillaStatus(this.vanillaEnabled, this.vanillaVerifyEveryBatch, this.vanillaFallbacks.get(),
            this.vanillaParityFailures.get(), result, this.vanillaMode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), this.vanillaAsync);
    }

    public int terrainDeviceCount() { return this.devices.size(); }
    public int terrainBatchSize(int deviceIndex) { return this.devices.get(deviceIndex).terrainBatchSize; }

    public record TerrainDeviceStatus(int index, String uuid, String name, boolean available, int batchSize,
                                      long gpuChunks, long batches, long averageDispatchNanos,
                                      long maxDispatchNanos, long paritySamples) {}

    public List<TerrainDeviceStatus> terrainDeviceStatuses() {
        List<TerrainDeviceStatus> result = new ArrayList<>(this.devices.size());
        for (int i = 0; i < this.devices.size(); i++) {
            DeviceState state = this.devices.get(i);
            long dispatches = state.terrainDispatches.get();
            result.add(new TerrainDeviceStatus(i, state.device.uuid(), state.device.name(),
                !this.resultGate.isClosed() && state.device.available(), state.terrainBatchSize,
                state.terrainChunks.get(), state.terrainBatches.get(),
                dispatches == 0 ? 0 : state.terrainDispatchNanos.get() / dispatches,
                state.terrainMaxDispatchNanos.get(), state.terrainParitySamples.get()));
        }
        return List.copyOf(result);
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
        check(device, org.gpur.terrain.TerrainRules.input(0x47507572426L, -64, 384, 63, -17, 21, 1874998, -1874998));
        double[][][] lower = new double[2][5][4];
        double[][][] upper = new double[2][5][4];
        for (int i = 0; i < lower.length; i++) {
            for (int z = 0; z < lower[i].length; z++) {
                for (int y = 0; y < lower[i][z].length; y++) {
                    lower[i][z][y] = (random.nextDouble() - 0.5) * (i == 0 ? 100 : 1e-100);
                    upper[i][z][y] = (random.nextDouble() - 0.5) * (i == 0 ? 100 : 1e-100);
                }
            }
        }
        check(device, VanillaTerrainInterpolation.input(4, 8, 3, lower, upper));
    }

    private static void check(VulkanDevice device, int[] input) {
        int[] actual = device.compute(input, ExactCompute.outputWords(input), input[1]);
        if (!ExactCompute.equal(ExactCompute.reference(input), actual, input[0])) {
            throw new IllegalStateException("CPU/GPU parity self-test failed");
        }
    }

    @Override
    public void close() {
        this.close(true);
    }

    /** Retires GPU results on a live reload; existing chunk owners finish on their CPU queue. */
    public void retireForReload() {
        this.close(false);
    }

    private void close(boolean cancelGenerations) {
        synchronized (this.generationLifecycle) {
            this.retiring = true;
            if (cancelGenerations) this.generationsClosed = true;
        }
        this.resultGate.close();
        // Cancel owners before completing numeric fallback futures: no halted CPU queue can
        // strand a chunk's section leases, and closing the service cannot resume block mutation.
        if (cancelGenerations) this.pendingGenerations.forEach(result -> result.cancel(false));
        this.pendingVanilla.forEach(result -> result.complete(null));
        this.pendingWorldgen.forEach(result -> result.complete(null));
        this.worldgenFrames.forEach(WorldgenResult::close);
        for (DeviceState state : this.devices) state.device.close();
        synchronized (this.generationLifecycle) {
            this.devicesClosed = true;
            this.completeRetirementIfDrained();
        }
    }

    private static final class DeviceState {
        private final VulkanDevice device;
        private final WorkloadState[] workloads = {null, new WorkloadState(), new WorkloadState()};
        private final int terrainBatchSize;
        private final AtomicLong terrainDispatches = new AtomicLong();
        private final AtomicLong terrainDispatchNanos = new AtomicLong();
        private final AtomicLong terrainMaxDispatchNanos = new AtomicLong();
        private final AtomicLong terrainChunks = new AtomicLong();
        private final AtomicLong terrainBatches = new AtomicLong();
        private final AtomicLong terrainParitySamples = new AtomicLong();
        private final AtomicLong vanillaDispatches = new AtomicLong();
        private final AtomicLong vanillaDispatchNanos = new AtomicLong();
        private final AtomicLong vanillaMaxDispatchNanos = new AtomicLong();
        private final AtomicLong vanillaSlabs = new AtomicLong();
        private final AtomicLong vanillaValues = new AtomicLong();
        private final AtomicLong vanillaParitySamples = new AtomicLong();
        private final AtomicLong vanillaObservedSlabs = new AtomicLong();
        private final AtomicLong vanillaVerificationNanos = new AtomicLong();
        private final DeviceLoadEstimator load = new DeviceLoadEstimator();
        private final WorldgenState[] worldgen = {null, null, null, null, null, new WorldgenState(), new WorldgenState()};
        private final VanillaVerificationPolicy verification = new VanillaVerificationPolicy(GPurConfig.vanillaTerrainMode,
            GPurConfig.gpuFullFirstBatches, GPurConfig.gpuVerificationSampleOneIn);

        private DeviceState(VulkanDevice device, int terrainBatchSize) {
            this.device = device;
            this.terrainBatchSize = terrainBatchSize;
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

    private static final class WorldgenState {
        private final AtomicLong dispatches = new AtomicLong();
        private final AtomicLong dispatchNanos = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong values = new AtomicLong();
        private final AtomicLong consumedValues = new AtomicLong();
        private final AtomicLong paritySamples = new AtomicLong();
        private final DeviceLoadEstimator load = new DeviceLoadEstimator();
        private final VanillaVerificationPolicy verification = new VanillaVerificationPolicy(GPurConfig.vanillaTerrainMode,
            GPurConfig.gpuFullFirstBatches, GPurConfig.gpuVerificationSampleOneIn);
    }
}
