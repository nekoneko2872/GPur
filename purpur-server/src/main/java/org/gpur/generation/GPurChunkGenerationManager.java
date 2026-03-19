package org.gpur.generation;

import ca.spottedleaf.concurrentutil.numa.OSNuma;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import org.gpur.GPurConfig;
import org.gpur.GPurLogVerbosity;
import org.gpur.generation.backend.GPurComputeBackend;
import org.gpur.generation.backend.GPurCpuComputeBackend;
import org.gpur.generation.backend.GPurVulkanComputeBackend;
import org.gpur.gpu.GPurAntiXrayBatchRequest;
import org.gpur.gpu.GPurAntiXrayBatchResult;
import org.gpur.gpu.GPurMobSpawnBatchResult;
import org.gpur.gpu.GPurStructureScanResult;

public final class GPurChunkGenerationManager {
    private static final Logger LOGGER = Logger.getLogger("GPur");
    private static final long GPU_WAIT_TIMEOUT_MILLIS = 750L;
    private static final long TERRAIN_GPU_TIMEOUT_COOLDOWN_MILLIS = 10_000L;
    private static final long GPU_REINIT_RETRY_MILLIS = 15_000L;
    private static final long DEFAULT_GPU_CHUNK_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);

    private final AtomicInteger activeNoiseTasks = new AtomicInteger();
    private final AtomicInteger terrainBatchesInFlight = new AtomicInteger();
    private final AtomicBoolean loggedSuccessfulGpuBatch = new AtomicBoolean();
    private final AtomicBoolean loggedSuccessfulAntiXrayBatch = new AtomicBoolean();
    private final AtomicBoolean loggedSuccessfulStructureScan = new AtomicBoolean();
    private final AtomicBoolean loggedSuccessfulMobScan = new AtomicBoolean();
    private final AtomicInteger completedGpuBatches = new AtomicInteger();
    private final AtomicInteger completedGpuChunks = new AtomicInteger();
    private final AtomicLong completedTerrainAssistDispatches = new AtomicLong();
    private final AtomicLong completedTerrainAssistColumns = new AtomicLong();
    private final AtomicLong completedTerrainAssistValues = new AtomicLong();
    private final AtomicLong completedTerrainAssistNanos = new AtomicLong();
    private final AtomicInteger completedAntiXraySections = new AtomicInteger();
    private final AtomicInteger completedStructureScans = new AtomicInteger();
    private final AtomicInteger completedMobScanCandidates = new AtomicInteger();
    private final AtomicLong rollingGpuChunkNanos = new AtomicLong(DEFAULT_GPU_CHUNK_NANOS);
    private final ThreadLocal<GPurNoiseStageContext> stageContext = new ThreadLocal<>();
    private final Deque<PendingTerrainRequest> pendingTerrainRequests = new LinkedList<>();

    private volatile GPurComputeBackend backend = new GPurCpuComputeBackend();
    private volatile long terrainGpuCooldownUntilNanos;
    private volatile long terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
    private volatile long terrainAssistOverloadSinceNanos = Long.MIN_VALUE;
    private volatile long lastGpuReinitAttemptMillis;
    private volatile String backendDetail = "GPur backend not initialized yet.";

    public synchronized void reload(final MinecraftServer server) {
        failPendingRequests("GPur generation manager reloaded.");
        this.terrainBatchesInFlight.set(0);
        this.loggedSuccessfulGpuBatch.set(false);
        this.loggedSuccessfulAntiXrayBatch.set(false);
        this.loggedSuccessfulStructureScan.set(false);
        this.loggedSuccessfulMobScan.set(false);
        this.completedGpuBatches.set(0);
        this.completedGpuChunks.set(0);
        this.completedTerrainAssistDispatches.set(0L);
        this.completedTerrainAssistColumns.set(0);
        this.completedTerrainAssistValues.set(0L);
        this.completedTerrainAssistNanos.set(0L);
        this.completedAntiXraySections.set(0);
        this.completedStructureScans.set(0);
        this.completedMobScanCandidates.set(0);
        this.rollingGpuChunkNanos.set(DEFAULT_GPU_CHUNK_NANOS);
        this.terrainGpuCooldownUntilNanos = 0L;
        this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
        this.terrainAssistOverloadSinceNanos = Long.MIN_VALUE;
        this.lastGpuReinitAttemptMillis = 0L;
        this.backendDetail = "GPur reload in progress.";

        final GPurComputeBackend previous = this.backend;
        this.backend = new GPurCpuComputeBackend();
        previous.close();

        if (!GPurConfig.gpuAccelerationEnabled) {
            this.backendDetail = "GPU acceleration disabled in gpur.yml.";
            LOGGER.info("GPur GPU acceleration is disabled in gpur.yml; using CPU mode.");
            return;
        }

        this.initializeGpuBackend("startup");
    }

    public AutoCloseable beginNoiseStage(final ServerLevel level, final ChunkPos chunkPos) {
        this.activeNoiseTasks.incrementAndGet();
        this.stageContext.set(new GPurNoiseStageContext(level, chunkPos));
        return new GPurNoiseStage(this);
    }

    public GPurChunkTerrainData generateTerrainIfEligible(
        final RandomState randomState,
        final NoiseGeneratorSettings generatorSettings,
        final ChunkAccess chunk
    ) {
        if (!GPurConfig.terrainGpuEnabled || !GPurConfig.legacyTerrainBatchEnabled) {
            return null;
        }

        final GPurNoiseStageContext context = this.stageContext.get();
        if (context == null) {
            return null;
        }

        final TerrainQueueSnapshot queueSnapshot = this.snapshotForQueuedRequest();
        if (!this.shouldQueueGpuRequest(queueSnapshot)) {
            return null;
        }

        final NoiseSettings noiseSettings = generatorSettings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        final GPurTerrainProfile profile = new GPurTerrainProfile(
            context.level().dimension().identifier().toString(),
            randomState.levelSeed(),
            noiseSettings.minY(),
            noiseSettings.height(),
            generatorSettings.seaLevel(),
            -54
        );

        final PendingTerrainRequest pendingRequest = new PendingTerrainRequest(
            new GPurChunkWorkItem(profile, chunk.getPos().x, chunk.getPos().z, System.nanoTime()),
            new CompletableFuture<>()
        );

        synchronized (this) {
            this.pendingTerrainRequests.addLast(pendingRequest);
        }

        this.trySubmitTerrainBatches(false);

        try {
            return this.awaitTerrainResult(pendingRequest);
        } catch (final TimeoutException timeoutException) {
            final boolean requestWasStillQueued = this.removePendingTerrainRequest(pendingRequest);
            pendingRequest.future().completeExceptionally(timeoutException);
            if (requestWasStillQueued || this.terrainBatchesInFlight.get() == 0) {
                LOGGER.warning("GPur terrain request outlived the batching window; using CPU for this chunk.");
            } else {
                LOGGER.warning("GPur GPU terrain batch exceeded the wait timeout; temporarily routing terrain generation back to CPU.");
                this.suspendTerrainGpu();
            }
            return null;
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            this.removePendingTerrainRequest(pendingRequest);
            LOGGER.log(Level.WARNING, "Interrupted while waiting for GPur GPU terrain batch, using CPU for this chunk.", interruptedException);
            return null;
        } catch (final Exception exception) {
            this.removePendingTerrainRequest(pendingRequest);
            LOGGER.log(Level.WARNING, "GPur GPU terrain generation failed, falling back to CPU mode.", exception);
            this.fallbackToCpu("GPur terrain generation failed: " + summarizeThrowable(exception));
            return null;
        }
    }

    void completeNoiseStage() {
        this.activeNoiseTasks.updateAndGet(current -> Math.max(0, current - 1));
        this.stageContext.remove();
    }

    private boolean canUseGpuBatch() {
        if (this.backend.mode() != GPurComputeMode.VULKAN || !this.backend.isAvailable()) {
            this.maybeRecoverGpuBackend();
        }

        if (this.backend.mode() != GPurComputeMode.VULKAN || !this.backend.isAvailable()) {
            return false;
        }

        return this.backend.currentUtilizationPercent().isEmpty()
            || this.backend.currentUtilizationPercent().getAsInt() < GPurConfig.gpuUsageFallback;
    }

    private boolean canUseLowLatencyGpuOffload() {
        if (!this.canUseGpuBatch()) {
            return false;
        }

        if (this.terrainBatchesInFlight.get() > 0 || this.activeNoiseTasks.get() > 0) {
            return false;
        }

        return this.backend.currentUtilizationPercent().isEmpty() || this.backend.currentUtilizationPercent().getAsInt() == 0;
    }

    private boolean canUsePacketGpuOffload() {
        if (!this.canUseGpuBatch()) {
            return false;
        }

        if (this.terrainBatchesInFlight.get() > 0) {
            return false;
        }

        final int totalContexts = this.backend.totalExecutionContexts();
        return totalContexts <= 0 || this.backend.busyExecutionContexts() < totalContexts;
    }

    public GPurAntiXrayBatchResult submitAntiXrayBatch(final GPurAntiXrayBatchRequest request) {
        final int minimumSections = Math.max(1, GPurConfig.antiXrayGpuMinSections);
        if (!GPurConfig.antiXrayGpuEnabled || request.sections().size() < minimumSections || !this.canUsePacketGpuOffload()) {
            return null;
        }

        try {
            final GPurAntiXrayBatchResult result = this.backend.submitAntiXrayBatch(request);
            if (result == null) {
                return null;
            }

            final int totalSections = this.completedAntiXraySections.addAndGet(result.sections().size());
            if (this.shouldLog(GPurConfig.antiXrayLogVerbosity, totalSections, totalSections, 1024L, 8192L)
                && (this.loggedSuccessfulAntiXrayBatch.compareAndSet(false, true) || totalSections % 8192 == 0 || GPurConfig.antiXrayLogVerbosity == GPurLogVerbosity.VERBOSE)) {
                LOGGER.info(
                    "GPur GPU anti-xray batch completed: "
                        + result.sections().size()
                        + " section(s) (total GPU sections="
                        + totalSections
                        + ")"
                );
            }
            return result;
        } catch (final Throwable throwable) {
            LOGGER.log(Level.WARNING, "GPur GPU anti-xray processing failed for this packet, using Paper CPU path.", throwable);
            return null;
        }
    }

    public int[] orderStructureCandidates(final BlockPos origin, final List<BlockPos> candidatePositions) {
        if (!GPurConfig.structureScanGpuEnabled || candidatePositions.size() < GPurConfig.structureScanGpuMinCandidates || !this.canUseLowLatencyGpuOffload()) {
            return null;
        }

        try {
            final GPurStructureScanResult result = this.backend.scanStructureCandidates(origin.getX(), origin.getZ(), candidatePositions);
            if (result == null || result.distanceSquared().length != candidatePositions.size()) {
                return null;
            }

            final Integer[] boxedOrder = new Integer[candidatePositions.size()];
            for (int index = 0; index < boxedOrder.length; index++) {
                boxedOrder[index] = index;
            }
            Arrays.sort(boxedOrder, (left, right) -> {
                final int compare = Float.compare(result.distanceSquared()[left], result.distanceSquared()[right]);
                return compare != 0 ? compare : Integer.compare(left, right);
            });

            final int[] order = new int[boxedOrder.length];
            for (int index = 0; index < boxedOrder.length; index++) {
                order[index] = boxedOrder[index];
            }

            final int scanCount = this.completedStructureScans.incrementAndGet();
            if (this.shouldLog(GPurConfig.structureScanLogVerbosity, scanCount, scanCount, 32L, 256L)
                && (this.loggedSuccessfulStructureScan.compareAndSet(false, true) || scanCount % 256 == 0 || GPurConfig.structureScanLogVerbosity == GPurLogVerbosity.VERBOSE)) {
                LOGGER.info(
                    "GPur GPU structure scan completed: "
                        + candidatePositions.size()
                        + " candidate(s)"
                        + this.structureOriginLogSuffix(origin)
                        + " (total scans="
                        + scanCount
                        + ")"
                );
            }
            return order;
        } catch (final Throwable throwable) {
            LOGGER.log(Level.WARNING, "GPur GPU structure scan failed, using CPU candidate order.", throwable);
            this.fallbackToCpu("GPur structure scan failed: " + summarizeThrowable(throwable));
            return null;
        }
    }

    public float[] scanMobSpawnCandidates(final List<Vec3> candidatePositions, final List<Vec3> playerPositions) {
        final int candidateCount = candidatePositions.size();
        final int playerCount = playerPositions.size();
        if (!this.shouldUseMobSpawnGpuScan(candidateCount, playerCount)) {
            return null;
        }

        try {
            final GPurMobSpawnBatchResult result = this.backend.scanMobSpawnCandidates(candidatePositions, playerPositions);
            if (result == null || result.nearestDistanceSquared().length != candidateCount) {
                return null;
            }

            final int totalCandidates = this.completedMobScanCandidates.addAndGet(candidateCount);
            if (this.shouldLog(GPurConfig.mobSpawnLogVerbosity, totalCandidates, totalCandidates, 8192L, 65536L)
                && (this.loggedSuccessfulMobScan.compareAndSet(false, true) || totalCandidates % 65536 == 0 || GPurConfig.mobSpawnLogVerbosity == GPurLogVerbosity.VERBOSE)) {
                LOGGER.info(
                    "GPur GPU mob spawn scan completed: "
                        + candidateCount
                        + " candidate(s) against "
                        + playerCount
                        + " player(s) (total scanned="
                        + totalCandidates
                        + ")"
                );
            }
            return result.nearestDistanceSquared();
        } catch (final Throwable throwable) {
            LOGGER.log(Level.WARNING, "GPur GPU mob spawn scan failed, using CPU nearest-player lookup.", throwable);
            this.fallbackToCpu("GPur mob spawn scan failed: " + summarizeThrowable(throwable));
            return null;
        }
    }

    public float[] interpolateNoiseColumn(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return this.interpolateNoiseSlice(cellWidth, cellHeight, cellCountY, 1, interpolatorCount, packedCorners);
    }

    public float[] interpolateNoiseSlice(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        if (!this.shouldUseTerrainAssist()) {
            return null;
        }

        final long startedAt = System.nanoTime();
        try {
            final float[] values = this.backend.interpolateNoiseSlice(
                cellWidth,
                cellHeight,
                cellCountY,
                cellCountZ,
                interpolatorCount,
                packedCorners
            );
            if (values == null) {
                return null;
            }

            final long totalDispatches = this.completedTerrainAssistDispatches.incrementAndGet();
            final long totalColumns = this.completedTerrainAssistColumns.addAndGet(cellCountZ);
            final long totalValues = this.completedTerrainAssistValues.addAndGet(values.length);
            this.completedTerrainAssistNanos.addAndGet(System.nanoTime() - startedAt);
            if (this.shouldLog(GPurConfig.terrainAssistLogVerbosity, totalDispatches, totalColumns, 128L, 4096L)) {
                LOGGER.info(
                    "GPur GPU terrain assist completed: "
                        + interpolatorCount
                        + " interpolator(s) across "
                        + cellCountZ
                        + " column(s) and "
                        + cellCountY
                        + " cell(s) (total dispatches="
                        + totalDispatches
                        + ", columns="
                        + totalColumns
                        + ", samples="
                        + totalValues
                        + ")"
                );
            }
            return values;
        } catch (final Throwable throwable) {
            LOGGER.log(Level.WARNING, "GPur GPU terrain assist failed for this interpolation slice, using CPU interpolation.", throwable);
            return null;
        }
    }

    private boolean shouldUseTerrainAssist() {
        if (!GPurConfig.terrainGpuEnabled || !this.canUseGpuBatch()) {
            return false;
        }

        if (!GPurConfig.terrainGpuHeavyLoadOnly) {
            return true;
        }

        return this.isTerrainAssistHeavyLoad();
    }

    private boolean isTerrainAssistHeavyLoad() {
        final boolean overloadedNow = this.activeNoiseTasks.get() >= this.effectiveTerrainAssistActiveNoiseThreshold();
        if (!overloadedNow) {
            this.terrainAssistOverloadSinceNanos = Long.MIN_VALUE;
            return false;
        }

        if (GPurConfig.terrainGpuHeavyLoadSustainMillis <= 0) {
            this.terrainAssistOverloadSinceNanos = System.nanoTime();
            return true;
        }

        final long now = System.nanoTime();
        final long overloadedSince = this.terrainAssistOverloadSinceNanos;
        if (overloadedSince == Long.MIN_VALUE) {
            this.terrainAssistOverloadSinceNanos = now;
            return false;
        }

        return now - overloadedSince >= TimeUnit.MILLISECONDS.toNanos(GPurConfig.terrainGpuHeavyLoadSustainMillis);
    }

    private boolean isTerrainAssistHeavyLoadActiveSnapshot() {
        if (!GPurConfig.terrainGpuEnabled) {
            return false;
        }

        if (!GPurConfig.terrainGpuHeavyLoadOnly) {
            return true;
        }

        if (this.activeNoiseTasks.get() < this.effectiveTerrainAssistActiveNoiseThreshold()) {
            return false;
        }

        if (GPurConfig.terrainGpuHeavyLoadSustainMillis <= 0) {
            return true;
        }

        final long overloadedSince = this.terrainAssistOverloadSinceNanos;
        return overloadedSince != Long.MIN_VALUE
            && System.nanoTime() - overloadedSince >= TimeUnit.MILLISECONDS.toNanos(GPurConfig.terrainGpuHeavyLoadSustainMillis);
    }

    private boolean shouldLog(
        final GPurLogVerbosity verbosity,
        final long dispatchCount,
        final long workCount,
        final long verboseInterval,
        final long summaryInterval
    ) {
        if (verbosity == GPurLogVerbosity.OFF) {
            return false;
        }

        if (dispatchCount <= 1L) {
            return true;
        }

        return verbosity == GPurLogVerbosity.VERBOSE
            ? dispatchCount % Math.max(1L, verboseInterval) == 0L
            : workCount % Math.max(1L, summaryInterval) == 0L;
    }

    private boolean hasSufficientMobSpawnWork(final int candidateCount, final int playerCount) {
        final long distanceChecks = (long)candidateCount * (long)playerCount;
        return distanceChecks >= GPurConfig.mobSpawnGpuMinDistanceChecks;
    }

    public boolean shouldUseMobSpawnGpuScan(final int candidateCount, final int playerCount) {
        if (!GPurConfig.mobSpawnGpuEnabled
            || candidateCount < GPurConfig.mobSpawnGpuMinCandidates
            || playerCount < GPurConfig.mobSpawnGpuMinPlayers
            || playerCount == 0
            || !this.hasSufficientMobSpawnWork(candidateCount, playerCount)) {
            return false;
        }

        return this.canUseLowLatencyGpuOffload();
    }

    private boolean shouldQueueGpuRequest(final TerrainQueueSnapshot snapshot) {
        if (!GPurConfig.terrainGpuEnabled) {
            return false;
        }
        if (this.isTerrainGpuCoolingDown()) {
            return false;
        }
        if (!this.canUseGpuBatch()) {
            return false;
        }
        if (!this.isTerrainGpuHeavyLoad(snapshot.projectedPendingCount(), snapshot.oldestPendingAgeMillis())) {
            return false;
        }

        final int dynamicMinBatchSize = this.dynamicMinBatchSize(snapshot.projectedPendingCount(), snapshot.oldestPendingAgeMillis());
        return snapshot.projectedPendingCount() >= dynamicMinBatchSize
            || this.activeNoiseTasks.get() >= Math.max(dynamicMinBatchSize, this.effectiveGpuQueueThreshold());
    }

    private boolean shouldDispatchGpuBatch() {
        if (!GPurConfig.terrainGpuEnabled) {
            return false;
        }
        if (this.isTerrainGpuCoolingDown()) {
            return false;
        }
        if (!this.canUseGpuBatch()) {
            return false;
        }

        final int pendingCount = this.pendingTerrainRequestCount();
        if (pendingCount <= 0) {
            return false;
        }

        final long oldestPendingAgeMillis = this.oldestPendingAgeMillis();
        if (!this.isTerrainGpuHeavyLoad(pendingCount, oldestPendingAgeMillis)) {
            return false;
        }

        final int minimumDispatchSize = GPurConfig.terrainGpuHeavyLoadOnly ? Math.max(2, this.effectiveGpuMinBatchSize()) : this.effectiveGpuMinBatchSize();
        if (pendingCount < minimumDispatchSize) {
            return false;
        }

        final int dynamicMinBatchSize = this.dynamicMinBatchSize(pendingCount, oldestPendingAgeMillis);
        if (pendingCount >= GPurConfig.gpuBatchSize) {
            return true;
        }
        if (pendingCount >= dynamicMinBatchSize) {
            return true;
        }

        return GPurConfig.gpuMaxQueueWaitMillis > 0
            && oldestPendingAgeMillis >= GPurConfig.gpuMaxQueueWaitMillis
            && pendingCount >= Math.max(this.effectiveGpuMinBatchSize(), dynamicMinBatchSize - 1);
    }

    private void trySubmitTerrainBatches(final boolean force) {
        while (this.canSubmitTerrainBatch(force)) {
            final List<PendingTerrainRequest> batch = this.pollNextBatch();
            if (batch.isEmpty()) {
                this.terrainBatchesInFlight.decrementAndGet();
                return;
            }

            final GPurBatchRequest request = new GPurBatchRequest(
                batch.getFirst().workItem().profile(),
                batch.stream().map(PendingTerrainRequest::workItem).toList(),
                GPurConfig.gpuBatchSize
            );

            this.backend.submitBatch(request).whenComplete((result, throwable) -> {
                this.terrainBatchesInFlight.decrementAndGet();
                if (throwable != null) {
                    batch.forEach(entry -> entry.future().completeExceptionally(throwable));
                    LOGGER.log(Level.WARNING, "GPur Vulkan terrain batch submission failed, switching back to CPU mode.", throwable);
                    this.fallbackToCpu("GPur terrain batch submission failed: " + summarizeThrowable(throwable));
                    return;
                }

                this.completeBatch(batch, result);

                if (this.hasPendingTerrainRequests()) {
                    this.trySubmitTerrainBatches(false);
                }
            });

            if (!force && !this.shouldDispatchGpuBatch()) {
                return;
            }
        }
    }

    private boolean canSubmitTerrainBatch(final boolean force) {
        if (!GPurConfig.terrainGpuEnabled) {
            return false;
        }
        if ((!force && !this.shouldDispatchGpuBatch()) || (force && (this.isTerrainGpuCoolingDown() || !this.canUseGpuBatch()))) {
            return false;
        }

        while (true) {
            final int inFlight = this.terrainBatchesInFlight.get();
            if (inFlight >= this.backend.maxTerrainBatchesInFlight()) {
                return false;
            }
            if (this.terrainBatchesInFlight.compareAndSet(inFlight, inFlight + 1)) {
                return true;
            }
        }
    }

    private TerrainQueueSnapshot snapshotForQueuedRequest() {
        synchronized (this) {
            return new TerrainQueueSnapshot(this.pendingTerrainRequests.size() + 1, this.oldestPendingAgeMillisUnsafe());
        }
    }

    private synchronized List<PendingTerrainRequest> pollNextBatch() {
        final PendingTerrainRequest anchor = this.pollNextPending();
        if (anchor == null) {
            return List.of();
        }

        final GPurTerrainProfile profile = anchor.workItem().profile();
        final List<PendingTerrainRequest> batch = new ArrayList<>(GPurConfig.gpuBatchSize);
        batch.add(anchor);

        final Iterator<PendingTerrainRequest> iterator = this.pendingTerrainRequests.iterator();
        while (iterator.hasNext() && batch.size() < GPurConfig.gpuBatchSize) {
            final PendingTerrainRequest candidate = iterator.next();
            if (candidate.future().isCancelled()) {
                iterator.remove();
                continue;
            }
            if (!candidate.workItem().profile().equals(profile)) {
                continue;
            }

            iterator.remove();
            batch.add(candidate);
        }

        return batch;
    }

    private synchronized PendingTerrainRequest pollNextPending() {
        while (!this.pendingTerrainRequests.isEmpty()) {
            final PendingTerrainRequest candidate = this.pendingTerrainRequests.removeFirst();
            if (!candidate.future().isCancelled()) {
                return candidate;
            }
        }
        return null;
    }

    private void completeBatch(final List<PendingTerrainRequest> batch, final GPurBatchResult result) {
        this.terrainGpuCooldownUntilNanos = 0L;
        final Map<String, GPurChunkTerrainData> terrainByChunk = new HashMap<>();
        result.chunks().forEach(terrain -> terrainByChunk.put(chunkKey(terrain.chunkX(), terrain.chunkZ()), terrain));

        for (final PendingTerrainRequest pendingTerrainRequest : batch) {
            final GPurChunkWorkItem workItem = pendingTerrainRequest.workItem();
            final GPurChunkTerrainData terrainData = terrainByChunk.remove(chunkKey(workItem.chunkX(), workItem.chunkZ()));
            if (terrainData == null) {
                pendingTerrainRequest.future().completeExceptionally(
                    new IllegalStateException("GPur GPU batch did not return terrain for chunk " + workItem.chunkX() + ", " + workItem.chunkZ())
                );
                continue;
            }

            pendingTerrainRequest.future().complete(terrainData);
        }

        final int batchCount = this.completedGpuBatches.incrementAndGet();
        final int chunkCount = this.completedGpuChunks.addAndGet(result.chunkCount());
        if (result.chunkCount() > 0) {
            final long batchChunkNanos = Math.max(1L, result.durationNanos() / result.chunkCount());
            this.rollingGpuChunkNanos.updateAndGet(current -> ((current * 3L) + batchChunkNanos) / 4L);
        }
        if (this.shouldLog(GPurConfig.terrainBatchLogVerbosity, batchCount, chunkCount, 16L, 128L)
            && (this.loggedSuccessfulGpuBatch.compareAndSet(false, true) || batchCount % 128 == 0 || GPurConfig.terrainBatchLogVerbosity == GPurLogVerbosity.VERBOSE)) {
            LOGGER.info(
                "GPur GPU terrain batch completed: "
                    + result.chunkCount()
                    + " chunk(s) in "
                    + TimeUnit.NANOSECONDS.toMillis(result.durationNanos())
                    + " ms on "
                    + this.deviceLabel(result.deviceName())
                    + " (total GPU chunks="
                    + chunkCount
                    + ", batches="
                    + batchCount
                    + ")"
            );
        }
    }

    private synchronized boolean hasPendingTerrainRequests() {
        return !this.pendingTerrainRequests.isEmpty();
    }

    private synchronized int pendingTerrainRequestCount() {
        return this.pendingTerrainRequests.size();
    }

    private synchronized long oldestPendingAgeMillis() {
        final long now = System.nanoTime();
        for (final PendingTerrainRequest request : this.pendingTerrainRequests) {
            if (request.future().isCancelled()) {
                continue;
            }

            return TimeUnit.NANOSECONDS.toMillis(now - request.workItem().enqueuedAtNanos());
        }

        return 0L;
    }

    private long oldestPendingAgeMillisUnsafe() {
        final long now = System.nanoTime();
        for (final PendingTerrainRequest request : this.pendingTerrainRequests) {
            if (request.future().isCancelled()) {
                continue;
            }

            return TimeUnit.NANOSECONDS.toMillis(now - request.workItem().enqueuedAtNanos());
        }

        return 0L;
    }

    private int dynamicMinBatchSize(final int pendingCount, final long oldestPendingAgeMillis) {
        int minBatchSize = GPurConfig.terrainGpuHeavyLoadOnly
            ? Math.max(2, this.effectiveGpuMinBatchSize())
            : (GPurConfig.turboModeEnabled ? Math.max(1, this.effectiveGpuMinBatchSize()) : Math.max(2, this.effectiveGpuMinBatchSize()));
        if (pendingCount >= GPurConfig.gpuBatchSize) {
            return minBatchSize;
        }

        final int utilizationPercent = this.backend.currentUtilizationPercent().orElse(0);
        if (!GPurConfig.turboModeEnabled && utilizationPercent >= 50) {
            minBatchSize += 2;
        }

        if (!GPurConfig.turboModeEnabled && this.activeNoiseTasks.get() < this.effectiveGpuQueueThreshold()) {
            minBatchSize = Math.max(minBatchSize, Math.min(GPurConfig.gpuBatchSize, 6));
        }

        final long gpuChunkMicros = TimeUnit.NANOSECONDS.toMicros(this.rollingGpuChunkNanos.get());
        if (!GPurConfig.turboModeEnabled && gpuChunkMicros > 600L) {
            minBatchSize += 1;
        }

        if (GPurConfig.gpuMaxQueueWaitMillis > 0
            && oldestPendingAgeMillis >= GPurConfig.gpuMaxQueueWaitMillis
            && this.activeNoiseTasks.get() < this.effectiveGpuQueueThreshold()) {
            minBatchSize = Math.max(minBatchSize, GPurConfig.turboModeEnabled ? Math.min(GPurConfig.gpuBatchSize, 2) : Math.min(GPurConfig.gpuBatchSize, 5));
        }

        if (GPurConfig.turboModeEnabled) {
            minBatchSize = Math.min(minBatchSize, Math.max(1, this.effectiveGpuMinBatchSize() + 1));
        }

        return Math.min(GPurConfig.gpuBatchSize, Math.max(this.effectiveGpuMinBatchSize(), minBatchSize));
    }

    private synchronized void fallbackToCpu() {
        this.fallbackToCpu("GPur backend requested CPU fallback.");
    }

    private synchronized void fallbackToCpu(final String reason) {
        final GPurComputeBackend previous = this.backend;
        this.backend = new GPurCpuComputeBackend();
        previous.close();
        this.terrainBatchesInFlight.set(0);
        this.terrainGpuCooldownUntilNanos = 0L;
        this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
        this.terrainAssistOverloadSinceNanos = Long.MIN_VALUE;
        this.backendDetail = reason;
        this.failPendingRequests("GPur switched back to CPU mode.");
    }

    private GPurChunkTerrainData awaitTerrainResult(final PendingTerrainRequest pendingRequest)
        throws InterruptedException, TimeoutException, java.util.concurrent.ExecutionException {
        long remainingTimeoutMillis = GPU_WAIT_TIMEOUT_MILLIS;
        final long queueWaitMillis = Math.min(remainingTimeoutMillis, Math.max(0L, GPurConfig.gpuMaxQueueWaitMillis));

        if (queueWaitMillis > 0L) {
            try {
                return pendingRequest.future().get(queueWaitMillis, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException ignored) {
                remainingTimeoutMillis = Math.max(1L, remainingTimeoutMillis - queueWaitMillis);
                if (!pendingRequest.future().isDone()) {
                    final TerrainQueueDecision postWaitDecision = this.decidePostWaitAction(pendingRequest);
                    if (postWaitDecision == TerrainQueueDecision.FALLBACK_TO_CPU && this.removePendingTerrainRequest(pendingRequest)) {
                        return null;
                    }
                    if (postWaitDecision == TerrainQueueDecision.DISPATCH_GPU_BATCH) {
                        this.trySubmitTerrainBatches(true);
                    }
                }
            }
        }

        return pendingRequest.future().get(remainingTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized boolean removePendingTerrainRequest(final PendingTerrainRequest pendingRequest) {
        return this.pendingTerrainRequests.remove(pendingRequest);
    }

    private void suspendTerrainGpu() {
        this.terrainGpuCooldownUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERRAIN_GPU_TIMEOUT_COOLDOWN_MILLIS);
        this.terrainBatchesInFlight.set(0);
        this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
        this.terrainAssistOverloadSinceNanos = Long.MIN_VALUE;
        this.failPendingRequests("GPur terrain GPU is temporarily suspended after a timeout.");
    }

    private boolean isTerrainGpuCoolingDown() {
        return System.nanoTime() < this.terrainGpuCooldownUntilNanos;
    }

    private synchronized void failPendingRequests(final String reason) {
        while (!this.pendingTerrainRequests.isEmpty()) {
            final PendingTerrainRequest request = this.pendingTerrainRequests.removeFirst();
            request.future().completeExceptionally(new IllegalStateException(reason));
        }
    }

    private static String chunkKey(final int chunkX, final int chunkZ) {
        return chunkX + ":" + chunkZ;
    }

    private TerrainQueueDecision decidePostWaitAction(final PendingTerrainRequest pendingRequest) {
        synchronized (this) {
            if (!this.pendingTerrainRequests.contains(pendingRequest)) {
                return TerrainQueueDecision.WAIT_FOR_IN_FLIGHT_BATCH;
            }

            final int pendingCount = this.pendingTerrainRequests.size();
            final long oldestPendingAgeMillis = this.oldestPendingAgeMillisUnsafe();
            if (!this.isTerrainGpuHeavyLoad(pendingCount, oldestPendingAgeMillis)) {
                return TerrainQueueDecision.FALLBACK_TO_CPU;
            }
            final int dynamicMinBatchSize = this.dynamicMinBatchSize(pendingCount, oldestPendingAgeMillis);
            if (pendingCount >= dynamicMinBatchSize || this.activeNoiseTasks.get() >= this.effectiveGpuQueueThreshold()) {
                return TerrainQueueDecision.DISPATCH_GPU_BATCH;
            }
            return TerrainQueueDecision.FALLBACK_TO_CPU;
        }
    }

    public GPurGenerationStatusSnapshot statusSnapshot() {
        return new GPurGenerationStatusSnapshot(
            this.backend.mode(),
            this.backend.deviceName(),
            this.backend.isAvailable(),
            this.backendDetail,
            GPurConfig.turboModeEnabled,
            GPurConfig.terrainGpuEnabled,
            GPurConfig.terrainGpuEnabled && this.isTerrainAssistHeavyLoadActiveSnapshot(),
            this.activeNoiseTasks.get(),
            this.effectiveTerrainAssistActiveNoiseThreshold(),
            this.backend.busyExecutionContexts(),
            this.backend.totalExecutionContexts(),
            this.completedTerrainAssistDispatches.get(),
            this.completedTerrainAssistColumns.get(),
            this.terrainAssistAverageMicrosPerColumn(),
            this.completedAntiXraySections.get(),
            this.completedStructureScans.get(),
            this.completedMobScanCandidates.get()
        );
    }

    private long terrainAssistAverageMicrosPerColumn() {
        final long totalColumns = this.completedTerrainAssistColumns.get();
        if (totalColumns <= 0L) {
            return 0L;
        }

        return TimeUnit.NANOSECONDS.toMicros(this.completedTerrainAssistNanos.get()) / totalColumns;
    }

    private String structureOriginLogSuffix(final BlockPos origin) {
        return GPurConfig.publicSafeLogging ? "" : " around " + origin.getX() + "," + origin.getZ();
    }

    private String deviceLabel(final String deviceName) {
        return GPurConfig.publicSafeLogging ? "configured GPU" : deviceName;
    }

    private int effectiveTerrainAssistActiveNoiseThreshold() {
        return Math.max(1, Math.min(GPurConfig.terrainGpuHeavyLoadMinActiveNoiseTasks, this.estimatedNoiseWorkerSaturationThreshold()));
    }

    private int estimatedNoiseWorkerSaturationThreshold() {
        return Math.max(1, this.estimatedNoiseWorkerThreads() - 1);
    }

    private int estimatedNoiseWorkerThreads() {
        final GlobalConfiguration configuration = GlobalConfiguration.get();
        final GlobalConfiguration.ChunkSystem chunkSystem = configuration == null ? null : configuration.chunkSystem;
        final int configuredWorkerThreads = chunkSystem == null ? -1 : chunkSystem.workerThreads;
        if (configuredWorkerThreads > 0) {
            return configuredWorkerThreads;
        }

        int defaultWorkerThreads = OSNuma.getNativeInstance().getTotalCores() / 2;
        if (defaultWorkerThreads <= 4) {
            defaultWorkerThreads = defaultWorkerThreads <= 3 ? 1 : 2;
        } else {
            defaultWorkerThreads = defaultWorkerThreads / 2;
        }

        return Integer.getInteger(
            PlatformHooks.get().getBrand() + ".WorkerThreadCount",
            Integer.valueOf(defaultWorkerThreads)
        );
    }

    private boolean isTerrainGpuHeavyLoad(final int pendingCount, final long oldestPendingAgeMillis) {
        if (!GPurConfig.terrainGpuEnabled) {
            this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
            return false;
        }
        if (!GPurConfig.terrainGpuHeavyLoadOnly) {
            this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
            return true;
        }

        final boolean overloadedNow = this.isTerrainGpuOverloadedNow(pendingCount, oldestPendingAgeMillis);
        if (!overloadedNow) {
            this.terrainGpuOverloadSinceNanos = Long.MIN_VALUE;
            return false;
        }

        if (GPurConfig.terrainGpuHeavyLoadSustainMillis <= 0) {
            this.terrainGpuOverloadSinceNanos = System.nanoTime();
            return true;
        }

        final long now = System.nanoTime();
        final long overloadedSince = this.terrainGpuOverloadSinceNanos;
        if (overloadedSince == Long.MIN_VALUE) {
            this.terrainGpuOverloadSinceNanos = now;
            return false;
        }

        return now - overloadedSince >= TimeUnit.MILLISECONDS.toNanos(GPurConfig.terrainGpuHeavyLoadSustainMillis);
    }

    private boolean isTerrainGpuOverloadedNow(final int pendingCount, final long oldestPendingAgeMillis) {
        return pendingCount >= GPurConfig.terrainGpuHeavyLoadMinPendingRequests
            || this.activeNoiseTasks.get() >= this.effectiveTerrainAssistActiveNoiseThreshold()
            || oldestPendingAgeMillis >= GPurConfig.terrainGpuHeavyLoadMinOldestPendingMillis;
    }

    private boolean isTerrainGpuHeavyLoadActiveSnapshot(final int pendingCount, final long oldestPendingAgeMillis) {
        if (!GPurConfig.terrainGpuEnabled) {
            return false;
        }
        if (!GPurConfig.terrainGpuHeavyLoadOnly) {
            return true;
        }

        if (!this.isTerrainGpuOverloadedNow(pendingCount, oldestPendingAgeMillis)) {
            return false;
        }

        if (GPurConfig.terrainGpuHeavyLoadSustainMillis <= 0) {
            return true;
        }

        final long overloadedSince = this.terrainGpuOverloadSinceNanos;
        return overloadedSince != Long.MIN_VALUE
            && System.nanoTime() - overloadedSince >= TimeUnit.MILLISECONDS.toNanos(GPurConfig.terrainGpuHeavyLoadSustainMillis);
    }

    private synchronized boolean initializeGpuBackend(final String attemptKind) {
        this.lastGpuReinitAttemptMillis = System.currentTimeMillis();
        try {
            final GPurComputeBackend previous = this.backend;
            this.backend = GPurVulkanComputeBackend.create();
            if (previous != null && previous != this.backend) {
                previous.close();
            }
            this.backendDetail = "Vulkan active (" + attemptKind + ").";
            LOGGER.info(
                GPurConfig.publicSafeLogging
                    ? "GPur Vulkan backend initialized."
                    : "GPur Vulkan backend initialized on " + this.backend.deviceName() + "."
            );
            return true;
        } catch (final Throwable throwable) {
            this.backend = new GPurCpuComputeBackend();
            this.backendDetail = "Vulkan " + attemptKind + " failed: " + summarizeThrowable(throwable);
            LOGGER.log(Level.WARNING, "GPur Vulkan initialization failed, continuing in CPU mode.", throwable);
            return false;
        }
    }

    private boolean maybeRecoverGpuBackend() {
        if (!GPurConfig.gpuAccelerationEnabled || this.backend.mode() == GPurComputeMode.VULKAN) {
            return this.backend.mode() == GPurComputeMode.VULKAN && this.backend.isAvailable();
        }

        final long now = System.currentTimeMillis();
        if (now - this.lastGpuReinitAttemptMillis < GPU_REINIT_RETRY_MILLIS) {
            return false;
        }

        return this.initializeGpuBackend("retry");
    }

    private static String summarizeThrowable(final Throwable throwable) {
        final String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private int effectiveGpuQueueThreshold() {
        if (!GPurConfig.turboModeEnabled) {
            return GPurConfig.gpuQueueThreshold;
        }

        return Math.max(1, Math.min(GPurConfig.gpuQueueThreshold, GPurConfig.turboGpuQueueThreshold));
    }

    private int effectiveGpuMinBatchSize() {
        if (!GPurConfig.turboModeEnabled) {
            return GPurConfig.gpuMinBatchSize;
        }

        return Math.max(1, Math.min(GPurConfig.gpuBatchSize, Math.min(GPurConfig.gpuMinBatchSize, GPurConfig.turboGpuMinBatchSize)));
    }

    private record PendingTerrainRequest(GPurChunkWorkItem workItem, CompletableFuture<GPurChunkTerrainData> future) {
    }

    private record TerrainQueueSnapshot(int projectedPendingCount, long oldestPendingAgeMillis) {
    }

    private enum TerrainQueueDecision {
        DISPATCH_GPU_BATCH,
        WAIT_FOR_IN_FLIGHT_BATCH,
        FALLBACK_TO_CPU
    }
}
