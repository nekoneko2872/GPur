package org.gpur.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;
import org.junit.jupiter.api.Test;

class GPurSharedPreloadSelectorTest {
    @Test
    void selectAnchorsOrdersByDistanceAndRecency() {
        final UUID playerId = UUID.randomUUID();
        final PreloadProfile profile = activeProfile(1.0D, 0.0D);
        final long nowNanos = 10_000L;

        final GPurSharedPreloadService.SharedPreloadAnchor olderSameDistance = anchor(UUID.randomUUID(), 1, 0, 9_000L);
        final GPurSharedPreloadService.SharedPreloadAnchor newerSameDistance = anchor(UUID.randomUUID(), 1, 1, 9_500L);
        final GPurSharedPreloadService.SharedPreloadAnchor farther = anchor(UUID.randomUUID(), 4, 0, 9_800L);

        final GPurSharedPreloadSelector.SelectionResult selection = GPurSharedPreloadSelector.selectAnchors(
            List.of(olderSameDistance, newerSameDistance, farther),
            playerId,
            0,
            0,
            profile,
            nowNanos,
            1_000L,
            8,
            0.2D,
            2
        );

        assertFalse(selection.removedExpiredAnchors());
        assertEquals(List.of(newerSameDistance, olderSameDistance), selection.anchors());
    }

    @Test
    void selectAnchorsDropsExpiredSelfAndBehindPlayerEntries() {
        final UUID playerId = UUID.randomUUID();
        final PreloadProfile profile = activeProfile(1.0D, 0.0D);
        final long nowNanos = 10_000L;

        final GPurSharedPreloadService.SharedPreloadAnchor selfAnchor = anchor(playerId, 1, 0, 9_800L);
        final GPurSharedPreloadService.SharedPreloadAnchor expiredAnchor = anchor(UUID.randomUUID(), 2, 0, 1_000L);
        final GPurSharedPreloadService.SharedPreloadAnchor behindAnchor = anchor(UUID.randomUUID(), -2, 0, -1.0D, 0.0D, 9_900L);

        final GPurSharedPreloadSelector.SelectionResult selection = GPurSharedPreloadSelector.selectAnchors(
            List.of(selfAnchor, expiredAnchor, behindAnchor),
            playerId,
            0,
            0,
            profile,
            nowNanos,
            2_000L,
            8,
            0.2D,
            4
        );

        assertTrue(selection.removedExpiredAnchors());
        assertTrue(selection.anchors().isEmpty());
    }

    @Test
    void inactiveProfileSkipsSelection() {
        final GPurSharedPreloadSelector.SelectionResult selection = GPurSharedPreloadSelector.selectAnchors(
            List.of(anchor(UUID.randomUUID(), 1, 0, 9_500L)),
            UUID.randomUUID(),
            0,
            0,
            GPurElytraPreloadMath.inactive(3, 60.0D),
            10_000L,
            1_000L,
            8,
            0.2D,
            4
        );

        assertFalse(selection.removedExpiredAnchors());
        assertTrue(selection.anchors().isEmpty());
    }

    private static PreloadProfile activeProfile(final double directionX, final double directionZ) {
        return new PreloadProfile(true, directionX, directionZ, 1.0D, 4, 0.5D);
    }

    private static GPurSharedPreloadService.SharedPreloadAnchor anchor(final UUID playerId, final int centerChunkX, final int centerChunkZ, final long updatedAtNanos) {
        return anchor(playerId, centerChunkX, centerChunkZ, 1.0D, 0.0D, updatedAtNanos);
    }

    private static GPurSharedPreloadService.SharedPreloadAnchor anchor(
        final UUID playerId,
        final int centerChunkX,
        final int centerChunkZ,
        final double directionX,
        final double directionZ,
        final long updatedAtNanos
    ) {
        return new GPurSharedPreloadService.SharedPreloadAnchor(playerId, centerChunkX, centerChunkZ, activeProfile(directionX, directionZ), updatedAtNanos);
    }
}