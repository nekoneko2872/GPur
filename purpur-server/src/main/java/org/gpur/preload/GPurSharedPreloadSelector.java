package org.gpur.preload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;

public final class GPurSharedPreloadSelector {
    private GPurSharedPreloadSelector() {
    }

    public static SelectionResult selectAnchors(
        final Collection<GPurSharedPreloadService.SharedPreloadAnchor> anchors,
        final UUID playerId,
        final int centerChunkX,
        final int centerChunkZ,
        final PreloadProfile profile,
        final long nowNanos,
        final long expiryWindowNanos,
        final int maxDistance,
        final double directionDotThreshold,
        final int maxAnchors
    ) {
        if (!profile.active() || anchors.isEmpty()) {
            return new SelectionResult(List.of(), List.of());
        }

        final long expiryNanos = nowNanos - expiryWindowNanos;
        final ArrayList<ScoredAnchor> selectedAnchors = new ArrayList<>();
        final ArrayList<UUID> expiredAnchorIds = new ArrayList<>();

        for (final GPurSharedPreloadService.SharedPreloadAnchor anchor : anchors) {
            if (anchor.playerId().equals(playerId)) {
                continue;
            }
            if (anchor.updatedAtNanos() < expiryNanos) {
                expiredAnchorIds.add(anchor.playerId());
                continue;
            }

            final int distance = Math.max(Math.abs(anchor.centerChunkX() - centerChunkX), Math.abs(anchor.centerChunkZ() - centerChunkZ));
            if (distance > maxDistance) {
                continue;
            }

            final double directionDot = (anchor.profile().directionX() * profile.directionX()) + (anchor.profile().directionZ() * profile.directionZ());
            if (directionDot < directionDotThreshold) {
                continue;
            }

            selectedAnchors.add(new ScoredAnchor(anchor, distance));
        }

        selectedAnchors.sort((left, right) -> {
            final int compare = Integer.compare(left.distance(), right.distance());
            if (compare != 0) {
                return compare;
            }
            return Long.compare(right.anchor().updatedAtNanos(), left.anchor().updatedAtNanos());
        });

        final int maxSelected = Math.max(1, maxAnchors);
        final int selectionSize = Math.min(selectedAnchors.size(), maxSelected);
        final ArrayList<GPurSharedPreloadService.SharedPreloadAnchor> anchorsView = new ArrayList<>(selectionSize);
        for (int index = 0; index < selectionSize; index++) {
            anchorsView.add(selectedAnchors.get(index).anchor());
        }

        return new SelectionResult(List.copyOf(anchorsView), List.copyOf(expiredAnchorIds));
    }

    public record SelectionResult(List<GPurSharedPreloadService.SharedPreloadAnchor> anchors, List<UUID> expiredAnchorIds) {
        public boolean removedExpiredAnchors() {
            return !this.expiredAnchorIds.isEmpty();
        }
    }

    private record ScoredAnchor(GPurSharedPreloadService.SharedPreloadAnchor anchor, int distance) {
    }
}