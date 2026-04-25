package org.gpur.reload;

import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.gpur.GPurConfig;

public final class GPurChunkReloadOptimizer {
    private static final TicketType<Long> HOT_RELOAD_TICKET = ChunkSystemTicketType.create(
        "gpur:hot_reload",
        Long::compareTo,
        12L,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
    );

    private final ConcurrentHashMap<RetainedChunkKey, Long> retainedChunks = new ConcurrentHashMap<>();
    private final AtomicInteger retainedRequests = new AtomicInteger();
    private final AtomicInteger retainedLoadHits = new AtomicInteger();

    public void reload() {
        this.retainedChunks.clear();
        this.retainedRequests.set(0);
        this.retainedLoadHits.set(0);
        ((ChunkSystemTicketType<Long>)(Object)HOT_RELOAD_TICKET).moonrise$setTimeout(Math.max(1L, GPurConfig.reloadHotCacheTicks));
    }

    public boolean retainForExplicitUnload(final ServerLevel level, final ChunkPos chunkPos) {
        if (!GPurConfig.reloadOptimizationEnabled || GPurConfig.reloadHotCacheTicks <= 0) {
            return false;
        }

        this.pruneExpired();

        final RetainedChunkKey key = RetainedChunkKey.of(level, chunkPos);
        if (!this.retainedChunks.containsKey(key) && this.retainedChunks.size() >= GPurConfig.reloadMaxRetainedChunks) {
            return false;
        }

        level.getChunkSource().addTicketAtLevel(HOT_RELOAD_TICKET, chunkPos, ChunkLevel.FULL_CHUNK_LEVEL);
        this.retainedChunks.put(key, this.computeExpiryNanos());
        this.retainedRequests.incrementAndGet();
        return true;
    }

    public boolean recordHotReloadHit(final ServerLevel level, final int chunkX, final int chunkZ) {
        if (!GPurConfig.reloadOptimizationEnabled || GPurConfig.reloadHotCacheTicks <= 0) {
            return false;
        }

        final RetainedChunkKey key = RetainedChunkKey.of(level, chunkX, chunkZ);
        final Long expiresAtNanos = this.retainedChunks.get(key);
        if (expiresAtNanos == null) {
            return false;
        }

        if (GPurReloadRetentionPolicy.isExpired(expiresAtNanos.longValue(), System.nanoTime())) {
            this.retainedChunks.remove(key, expiresAtNanos);
            return false;
        }

        this.retainedLoadHits.incrementAndGet();
        return true;
    }

    public int retainedRequests() {
        return this.retainedRequests.get();
    }

    public int retainedLoadHits() {
        return this.retainedLoadHits.get();
    }

    public int activeRetainedChunks() {
        this.pruneExpired();
        return this.retainedChunks.size();
    }

    private void pruneExpired() {
        final long now = System.nanoTime();
        this.retainedChunks.entrySet().removeIf(entry -> GPurReloadRetentionPolicy.isExpired(entry.getValue().longValue(), now));
    }

    private long computeExpiryNanos() {
        return GPurReloadRetentionPolicy.computeExpiryNanos(System.nanoTime(), GPurConfig.reloadHotCacheTicks);
    }

    private record RetainedChunkKey(String dimensionId, long chunkKey) {
        private static RetainedChunkKey of(final ServerLevel level, final ChunkPos chunkPos) {
            return new RetainedChunkKey(level.dimension().identifier().toString(), chunkPos.toLong());
        }

        private static RetainedChunkKey of(final ServerLevel level, final int chunkX, final int chunkZ) {
            return new RetainedChunkKey(level.dimension().identifier().toString(), ChunkPos.asLong(chunkX, chunkZ));
        }
    }
}
