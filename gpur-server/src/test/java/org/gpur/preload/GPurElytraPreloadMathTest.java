package org.gpur.preload;

import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GPurElytraPreloadMathTest {
    @Test
    void lookaheadMatchesSpeedExample() {
        final PreloadProfile profile = createProfile(1.5D, 0.0D, 1.0D, 0.0D);
        assertTrue(profile.active());
        assertEquals(18, profile.lookaheadChunks());
    }

    @Test
    void preloadConeAcceptsForwardChunks() {
        final PreloadProfile profile = createProfile(1.5D, 0.0D, 1.0D, 0.0D);
        assertTrue(GPurElytraPreloadMath.isWithinPreloadCone(profile, 0, 0, 10, 2));
    }

    @Test
    void preloadConeRejectsChunksBehindPlayer() {
        final PreloadProfile profile = createProfile(1.5D, 0.0D, 1.0D, 0.0D);
        assertFalse(GPurElytraPreloadMath.isWithinPreloadCone(profile, 0, 0, -4, 0));
    }

    @Test
    void turningTowardsLookDirectionRotatesPreloadCone() {
        final PreloadProfile profile = createProfile(1.5D, 0.0D, 0.0D, 1.0D);

        assertTrue(profile.directionX() > 0.5D);
        assertTrue(profile.directionZ() > 0.4D);
        assertTrue(GPurElytraPreloadMath.isWithinPreloadCone(profile, 0, 0, 6, 6));
    }

    @Test
    void turningWidensConeAndAddsLookahead() {
        final PreloadProfile straightProfile = createProfile(1.5D, 0.0D, 1.0D, 0.0D);
        final PreloadProfile turningProfile = createProfile(1.5D, 0.0D, 0.0D, 1.0D);

        assertTrue(turningProfile.lookaheadChunks() > straightProfile.lookaheadChunks());
        assertTrue(turningProfile.coneCosThreshold() < straightProfile.coneCosThreshold());
    }

    @Test
    void inactiveProfileRejectsAllChunks() {
        final PreloadProfile profile = GPurElytraPreloadMath.inactive(3, 60.0D);

        assertFalse(profile.active());
        assertFalse(GPurElytraPreloadMath.isWithinPreloadCone(profile, 0, 0, 1, 0));
    }

    @Test
    void forwardPriorityScorePrefersForwardChunks() {
        final PreloadProfile profile = createProfile(1.5D, 0.0D, 1.0D, 0.0D);

        assertTrue(GPurElytraPreloadMath.forwardPriorityScore(profile, 0, 0, 5, 0) > GPurElytraPreloadMath.forwardPriorityScore(profile, 0, 0, 0, 5));
    }

    @Test
    void priorityRefreshIgnoresSmallTurnsAndRecognizesLargerTurns() {
        PreloadProfile straight = new PreloadProfile(true, 1, 0, 30, 18, 0.5);
        double small = Math.toRadians(2);
        double large = Math.toRadians(8);
        assertFalse(GPurElytraPreloadMath.priorityChanged(straight,
            new PreloadProfile(true, Math.cos(small), Math.sin(small), 30, 18, 0.5)));
        assertTrue(GPurElytraPreloadMath.priorityChanged(straight,
            new PreloadProfile(true, Math.cos(large), Math.sin(large), 30, 18, 0.5)));
        assertTrue(GPurElytraPreloadMath.priorityChanged(straight, GPurElytraPreloadMath.inactive(3, 60)));
    }

    @Test
    void lookaheadOutsideTheQueueDoesNotCauseAnotherFullQueueRebuild() {
        PreloadProfile previous = new PreloadProfile(true, 1, 0, 30, 18, 0.5);
        PreloadProfile faster = new PreloadProfile(true, 1, 0, 32, 20, 0.5);
        assertFalse(GPurElytraPreloadMath.priorityChanged(previous, faster, 12));
        assertTrue(GPurElytraPreloadMath.priorityChanged(previous, faster, 19));
        assertTrue(GPurElytraPreloadMath.priorityChanged(previous,
            new PreloadProfile(true, 1, 0, 12, 8, 0.5), 12));
        assertTrue(GPurElytraPreloadMath.priorityChanged(previous, GPurElytraPreloadMath.inactive(3, 60), 12));
    }

    @Test
    void nonFiniteMotionAndLookCannotEnterQueueComparators() {
        assertFalse(createProfile(Double.NaN, 0, 1, 0).active());
        assertFalse(createProfile(1.5, Double.POSITIVE_INFINITY, 1, 0).active());
        assertFalse(createProfile(1.5, 0, Double.NEGATIVE_INFINITY, 0).active());
    }

    private static PreloadProfile createProfile(final double motionX, final double motionZ, final double lookX, final double lookZ) {
        return GPurElytraPreloadMath.fromMotion(true, true, motionX, motionZ, lookX, lookZ, 3, 0.6D, 60.0D, 0.35D, 6, 18.0D);
    }
}
