package org.gpur.preload;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.gpur.preload.GPurElytraPreloadMath.PreloadProfile;
import org.junit.jupiter.api.Test;

class GPurSharedPreloadServiceTest {
    @Test
    void timestampOnlyRefreshDoesNotBumpVersion() {
        final PreloadProfile profile = activeProfile();
        final GPurSharedPreloadService.SharedPreloadAnchor previousAnchor = anchor(profile, 4, 7, 1_000L);
        final GPurSharedPreloadService.SharedPreloadAnchor refreshedAnchor = anchor(profile, 4, 7, 2_000L);

        assertFalse(GPurSharedPreloadService.requiresVersionBump(previousAnchor, refreshedAnchor));
    }

    @Test
    void chunkOrProfileChangeBumpsVersion() {
        final PreloadProfile profile = activeProfile();
        final GPurSharedPreloadService.SharedPreloadAnchor previousAnchor = anchor(profile, 4, 7, 1_000L);

        assertTrue(GPurSharedPreloadService.requiresVersionBump(previousAnchor, anchor(profile, 5, 7, 2_000L)));
        assertTrue(GPurSharedPreloadService.requiresVersionBump(previousAnchor, anchor(new PreloadProfile(true, 0.0D, 1.0D, 24.0D, 6, 0.5D), 4, 7, 2_000L)));
        assertTrue(GPurSharedPreloadService.requiresVersionBump(null, anchor(profile, 4, 7, 2_000L)));
    }

    private static PreloadProfile activeProfile() {
        return new PreloadProfile(true, 1.0D, 0.0D, 24.0D, 6, 0.5D);
    }

    private static GPurSharedPreloadService.SharedPreloadAnchor anchor(
        final PreloadProfile profile,
        final int centerChunkX,
        final int centerChunkZ,
        final long updatedAtNanos
    ) {
        return new GPurSharedPreloadService.SharedPreloadAnchor(UUID.randomUUID(), centerChunkX, centerChunkZ, profile, updatedAtNanos);
    }
}