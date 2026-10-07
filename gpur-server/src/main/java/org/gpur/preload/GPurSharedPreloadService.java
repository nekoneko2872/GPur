package org.gpur.preload;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.gpur.GPurConfig;
import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;

public final class GPurSharedPreloadService {
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, SharedPreloadAnchor>> anchorsByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> worldVersions = new ConcurrentHashMap<>();
    private final AtomicLong activeAnchors = new AtomicLong();
    private final AtomicLong sharedSelections = new AtomicLong();
    private final AtomicLong sharedPriorityHits = new AtomicLong();
    private final AtomicLong retainedPriorityHits = new AtomicLong();
    private final AtomicLong sendBurstSends = new AtomicLong();
    private final AtomicLong turboSendBurstSends = new AtomicLong();

    public void reset() {
        this.anchorsByWorld.clear();
        this.worldVersions.clear();
        this.activeAnchors.set(0L);
        this.sharedSelections.set(0L);
        this.sharedPriorityHits.set(0L);
        this.retainedPriorityHits.set(0L);
        this.sendBurstSends.set(0L);
        this.turboSendBurstSends.set(0L);
    }

    public void updateAnchor(
        final ServerPlayer player,
        final ServerLevel world,
        final int centerChunkX,
        final int centerChunkZ,
        final PreloadProfile profile,
        final long nowNanos
    ) {
        if (!GPurConfig.sharedPreloadingEnabled || !profile.active()) {
            this.removeAnchor(player, world);
            return;
        }

        final String worldKey = this.worldKey(world);
        final Map<UUID, SharedPreloadAnchor> anchors = this.anchorsByWorld.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>());
        final AtomicLong versionCounter = this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong());
        final SharedPreloadAnchor nextAnchor = new SharedPreloadAnchor(player.getUUID(), centerChunkX, centerChunkZ, profile, nowNanos);
        final SharedPreloadAnchor previousAnchor = anchors.put(player.getUUID(), nextAnchor);
        if (previousAnchor == null) {
            this.activeAnchors.incrementAndGet();
        }
        if (requiresVersionBump(previousAnchor, nextAnchor)) {
            versionCounter.incrementAndGet();
        }
    }

    public void removeAnchor(final ServerPlayer player, final ServerLevel world) {
        final String worldKey = this.worldKey(world);
        final Map<UUID, SharedPreloadAnchor> anchors = this.anchorsByWorld.get(worldKey);
        if (anchors == null) {
            return;
        }

        if (anchors.remove(player.getUUID()) != null) {
            this.activeAnchors.decrementAndGet();
            this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong()).incrementAndGet();
        }
        if (anchors.isEmpty()) {
            this.anchorsByWorld.remove(worldKey, anchors);
        }
    }

    public SharedPreloadSelection selectAnchors(
        final ServerPlayer player,
        final ServerLevel world,
        final int centerChunkX,
        final int centerChunkZ,
        final PreloadProfile profile,
        final long nowNanos
    ) {
        final String worldKey = this.worldKey(world);
        final AtomicLong versionCounter = this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong());
        final long version = versionCounter.get();
        if (!GPurConfig.sharedPreloadingEnabled || !profile.active()) {
            return new SharedPreloadSelection(List.of(), version);
        }

        final Map<UUID, SharedPreloadAnchor> anchors = this.anchorsByWorld.get(worldKey);
        if (anchors == null || anchors.isEmpty()) {
            return new SharedPreloadSelection(List.of(), version);
        }

        final GPurSharedPreloadSelector.SelectionResult selection = GPurSharedPreloadSelector.selectAnchors(
            anchors.values(),
            player.getUUID(),
            centerChunkX,
            centerChunkZ,
            profile,
            nowNanos,
            TimeUnit.MILLISECONDS.toNanos(GPurConfig.sharedPreloadingAnchorExpiryMillis),
            GPurConfig.sharedPreloadingMaxDistance,
            GPurConfig.sharedPreloadingDirectionDotThreshold,
            GPurConfig.sharedPreloadingMaxAnchors
        );

        boolean removedExpiredAnchors = false;
        for (final UUID expiredAnchorId : selection.expiredAnchorIds()) {
            if (anchors.remove(expiredAnchorId) != null) {
                this.activeAnchors.decrementAndGet();
                removedExpiredAnchors = true;
            }
        }

        if (removedExpiredAnchors) {
            versionCounter.incrementAndGet();
        }

        if (!selection.anchors().isEmpty()) {
            this.sharedSelections.incrementAndGet();
        }

        return new SharedPreloadSelection(selection.anchors(), versionCounter.get());
    }

    public void recordSharedPriorityHit() {
        this.sharedPriorityHits.incrementAndGet();
    }

    public void recordRetainedPriorityHit() {
        this.retainedPriorityHits.incrementAndGet();
    }

    public void recordSendBurst(final boolean turbo) {
        this.sendBurstSends.incrementAndGet();
        if (turbo) {
            this.turboSendBurstSends.incrementAndGet();
        }
    }

    public GPurPreloadStatusSnapshot statusSnapshot() {
        return new GPurPreloadStatusSnapshot(
            (int)this.activeAnchors.get(),
            this.anchorsByWorld.size(),
            this.sharedSelections.get(),
            this.sharedPriorityHits.get(),
            this.retainedPriorityHits.get(),
            this.sendBurstSends.get(),
            this.turboSendBurstSends.get()
        );
    }

    public record SharedPreloadSelection(List<SharedPreloadAnchor> anchors, long version) {
    }

    public record SharedPreloadAnchor(
        UUID playerId,
        int centerChunkX,
        int centerChunkZ,
        PreloadProfile profile,
        long updatedAtNanos
    ) {
    }

    static boolean requiresVersionBump(final SharedPreloadAnchor previousAnchor, final SharedPreloadAnchor nextAnchor) {
        return previousAnchor == null
            || previousAnchor.centerChunkX() != nextAnchor.centerChunkX()
            || previousAnchor.centerChunkZ() != nextAnchor.centerChunkZ()
            || !previousAnchor.profile().equals(nextAnchor.profile());
    }

    private String worldKey(final ServerLevel world) {
        return world.dimension().identifier().toString();
    }
}
