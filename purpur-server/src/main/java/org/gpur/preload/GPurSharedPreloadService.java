package org.gpur.preload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.gpur.GPurConfig;
import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;

public final class GPurSharedPreloadService {
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, SharedPreloadAnchor>> anchorsByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> worldVersions = new ConcurrentHashMap<>();
    private final AtomicLong sharedSelections = new AtomicLong();
    private final AtomicLong sharedPriorityHits = new AtomicLong();
    private final AtomicLong retainedPriorityHits = new AtomicLong();
    private final AtomicLong sendBurstSends = new AtomicLong();
    private final AtomicLong turboSendBurstSends = new AtomicLong();

    public void reset() {
        this.anchorsByWorld.clear();
        this.worldVersions.clear();
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

        final String worldKey = world.dimension().identifier().toString();
        this.anchorsByWorld.computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>())
            .put(player.getUUID(), new SharedPreloadAnchor(player.getUUID(), centerChunkX, centerChunkZ, profile, nowNanos));
        this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong()).incrementAndGet();
    }

    public void removeAnchor(final ServerPlayer player, final ServerLevel world) {
        final String worldKey = world.dimension().identifier().toString();
        final Map<UUID, SharedPreloadAnchor> anchors = this.anchorsByWorld.get(worldKey);
        if (anchors == null) {
            return;
        }

        if (anchors.remove(player.getUUID()) != null) {
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
        final String worldKey = world.dimension().identifier().toString();
        final long version = this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong()).get();
        if (!GPurConfig.sharedPreloadingEnabled || !profile.active()) {
            return new SharedPreloadSelection(List.of(), version);
        }

        final Map<UUID, SharedPreloadAnchor> anchors = this.anchorsByWorld.get(worldKey);
        if (anchors == null || anchors.isEmpty()) {
            return new SharedPreloadSelection(List.of(), version);
        }

        final long expiryNanos = nowNanos - (GPurConfig.sharedPreloadingAnchorExpiryMillis * 1_000_000L);
        final ArrayList<SharedPreloadAnchor> selectedAnchors = new ArrayList<>();
        boolean removedExpiredAnchors = false;

        for (final SharedPreloadAnchor anchor : anchors.values()) {
            if (anchor.playerId().equals(player.getUUID())) {
                continue;
            }
            if (anchor.updatedAtNanos() < expiryNanos) {
                if (anchors.remove(anchor.playerId(), anchor)) {
                    removedExpiredAnchors = true;
                }
                continue;
            }

            final int squareDistance = Math.max(Math.abs(anchor.centerChunkX() - centerChunkX), Math.abs(anchor.centerChunkZ() - centerChunkZ));
            if (squareDistance > GPurConfig.sharedPreloadingMaxDistance) {
                continue;
            }

            final double directionDot = (anchor.profile().directionX() * profile.directionX()) + (anchor.profile().directionZ() * profile.directionZ());
            if (directionDot < GPurConfig.sharedPreloadingDirectionDotThreshold) {
                continue;
            }

            selectedAnchors.add(anchor);
        }

        if (removedExpiredAnchors) {
            this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong()).incrementAndGet();
        }

        selectedAnchors.sort(
            Comparator.<SharedPreloadAnchor>comparingInt(anchor ->
                Math.max(Math.abs(anchor.centerChunkX() - centerChunkX), Math.abs(anchor.centerChunkZ() - centerChunkZ))
            ).thenComparing(SharedPreloadAnchor::updatedAtNanos, Comparator.reverseOrder())
        );

        final int maxAnchors = Math.max(1, GPurConfig.sharedPreloadingMaxAnchors);
        final List<SharedPreloadAnchor> anchorsView = selectedAnchors.size() > maxAnchors
            ? List.copyOf(selectedAnchors.subList(0, maxAnchors))
            : List.copyOf(selectedAnchors);

        if (!anchorsView.isEmpty()) {
            this.sharedSelections.incrementAndGet();
        }

        return new SharedPreloadSelection(anchorsView, this.worldVersions.computeIfAbsent(worldKey, ignored -> new AtomicLong()).get());
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
        int activeAnchors = 0;
        for (final Map<UUID, SharedPreloadAnchor> anchors : this.anchorsByWorld.values()) {
            activeAnchors += anchors.size();
        }

        return new GPurPreloadStatusSnapshot(
            activeAnchors,
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
}
