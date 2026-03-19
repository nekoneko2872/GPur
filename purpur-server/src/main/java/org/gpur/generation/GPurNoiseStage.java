package org.gpur.generation;

final class GPurNoiseStage implements AutoCloseable {
    private final GPurChunkGenerationManager manager;
    private boolean closed;

    GPurNoiseStage(final GPurChunkGenerationManager manager) {
        this.manager = manager;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.manager.completeNoiseStage();
    }
}
