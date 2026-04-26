package org.gpur.generation.backend;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.gpur.generation.GPurBatchRequest;
import org.gpur.generation.GPurBatchResult;
import org.gpur.generation.GPurComputeMode;
import org.gpur.gpu.GPurAntiXrayBatchRequest;
import org.gpur.gpu.GPurAntiXrayBatchResult;
import org.gpur.gpu.GPurMobSpawnBatchResult;
import org.gpur.gpu.GPurStructureScanResult;

public interface GPurComputeBackend extends AutoCloseable {
    GPurComputeMode mode();

    String deviceName();

    boolean isAvailable();

    OptionalInt currentUtilizationPercent();

    default int busyExecutionContexts() {
        return 0;
    }

    default int totalExecutionContexts() {
        return maxTerrainBatchesInFlight();
    }

    default int busyPacketExecutionContexts() {
        return 0;
    }

    default int reservedPacketExecutionContexts() {
        return 0;
    }

    default int maxTerrainBatchesInFlight() {
        return 1;
    }

    CompletableFuture<GPurBatchResult> submitBatch(GPurBatchRequest request);

    default GPurAntiXrayBatchResult submitAntiXrayBatch(final GPurAntiXrayBatchRequest request) {
        return null;
    }

    default GPurStructureScanResult scanStructureCandidates(
        final int originBlockX,
        final int originBlockZ,
        final java.util.List<BlockPos> candidatePositions
    ) {
        return null;
    }

    default GPurMobSpawnBatchResult scanMobSpawnCandidates(
        final java.util.List<Vec3> candidatePositions,
        final java.util.List<Vec3> playerPositions
    ) {
        return null;
    }

    default float[] interpolateNoiseColumn(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return null;
    }

    default float[] interpolateNoiseSlice(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return cellCountZ == 1
            ? this.interpolateNoiseColumn(cellWidth, cellHeight, cellCountY, interpolatorCount, packedCorners)
            : null;
    }

    default CompletableFuture<float[]> interpolateNoiseSliceAsync(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return null;
    }

    @Override
    void close();
}
