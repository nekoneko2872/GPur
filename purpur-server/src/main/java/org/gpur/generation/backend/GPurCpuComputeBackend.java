package org.gpur.generation.backend;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import org.gpur.generation.GPurBatchRequest;
import org.gpur.generation.GPurBatchResult;
import org.gpur.generation.GPurComputeMode;

public final class GPurCpuComputeBackend implements GPurComputeBackend {
    @Override
    public GPurComputeMode mode() {
        return GPurComputeMode.CPU;
    }

    @Override
    public String deviceName() {
        return "CPU Fallback";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public OptionalInt currentUtilizationPercent() {
        return OptionalInt.of(0);
    }

    @Override
    public int maxTerrainBatchesInFlight() {
        return 1;
    }

    @Override
    public CompletableFuture<GPurBatchResult> submitBatch(final GPurBatchRequest request) {
        return CompletableFuture.completedFuture(new GPurBatchResult(this.mode(), this.deviceName(), request.chunks().size(), 0, 0L, java.util.List.of()));
    }

    @Override
    public void close() {
    }
}
