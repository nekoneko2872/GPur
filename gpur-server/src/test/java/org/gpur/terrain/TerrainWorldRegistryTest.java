package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.World;
import org.bukkit.generator.WorldInfo;
import org.gpur.GPurConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainWorldRegistryTest {
    private static final String LEGACY_MARKER = "schema=1\nversion=1\nseed=37\nmin-y=-64\nheight=384\nsea-level=63\n";

    @TempDir
    Path directory;

    @Test
    void restoresLegacyMarkerWhenGeneratorSettingWasRemovedEvenWhenCustomTerrainIsDisabled() throws Exception {
        Path world = this.directory.resolve("legacy-world");
        Files.createDirectories(world);
        Path marker = world.resolve(TerrainWorldRegistry.MARKER_FILENAME);
        Files.writeString(marker, LEGACY_MARKER);

        boolean previous = GPurConfig.terrainCustomEnabled;
        try {
            GPurConfig.terrainCustomEnabled = false;
            GPurTerrainGenerator restored = TerrainWorldRegistry.resolve(world, null);

            assertNotNull(restored);
            assertSame(restored, TerrainWorldRegistry.resolve(world, null));
            assertSame(restored, TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID));
            restored.validateWorldInfo(worldInfo(37L, -64, 320));
            assertEquals(LEGACY_MARKER, Files.readString(marker));
        } finally {
            GPurConfig.terrainCustomEnabled = previous;
        }
    }

    @Test
    void rejectsNewOptInWithActionableReasonAndLeavesWorldDataUntouched() throws Exception {
        Path emptyWorld = this.directory.resolve("new-world");
        IllegalStateException emptyFailure = assertThrows(IllegalStateException.class,
            () -> TerrainWorldRegistry.resolve(emptyWorld, TerrainWorldRegistry.GENERATOR_ID));
        assertTrue(emptyFailure.getMessage().contains("retired"));
        assertFalse(Files.exists(emptyWorld));

        Path world = this.directory.resolve("requested-custom-world");
        Path region = world.resolve("region");
        Files.createDirectories(region);
        Path existingRegion = region.resolve("r.0.0.mca");
        Files.write(existingRegion, new byte[] {1, 2, 3});

        boolean previous = GPurConfig.terrainCustomEnabled;
        try {
            // Retired opt-ins remain rejected even when an older config explicitly enabled them.
            GPurConfig.terrainCustomEnabled = true;
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID));

            assertTrue(failure.getMessage().contains("retired"));
            assertTrue(failure.getMessage().contains("original vanilla terrain generator"));
            assertTrue(failure.getMessage().contains("server.properties"));
            assertTrue(failure.getMessage().contains("left untouched"));
            assertFalse(Files.exists(world.resolve(TerrainWorldRegistry.MARKER_FILENAME)));
            assertTrue(Files.exists(existingRegion));
        } finally {
            GPurConfig.terrainCustomEnabled = previous;
        }
    }

    @Test
    void rejectsSeedOrHeightChangesForLegacyMarkedWorld() throws Exception {
        Path world = this.directory.resolve("immutable-world");
        writeLegacyMarker(world);
        GPurTerrainGenerator generator = TerrainWorldRegistry.resolve(world, null);
        generator.validateWorldInfo(worldInfo(37L, -64, 320));

        assertThrows(IllegalStateException.class, () -> generator.validateWorldInfo(worldInfo(38L, -64, 320)));
        assertThrows(IllegalStateException.class, () -> generator.validateWorldInfo(worldInfo(37L, -64, 384)));
    }

    @Test
    void rejectsChangingGeneratorOfLegacyMarkedWorld() throws Exception {
        Path world = this.directory.resolve("changed-generator-world");
        writeLegacyMarker(world);

        assertThrows(IllegalStateException.class, () -> TerrainWorldRegistry.resolve(world, "some-plugin:generator"));
    }

    @Test
    void failsClosedForMalformedOrUnsupportedLegacyMarkers() throws Exception {
        Path malformedWorld = this.directory.resolve("malformed-world");
        Files.createDirectories(malformedWorld);
        Files.writeString(malformedWorld.resolve(TerrainWorldRegistry.MARKER_FILENAME), "schema=1\nversion=1\nseed=broken\n");
        assertThrows(IllegalStateException.class, () -> TerrainWorldRegistry.resolve(malformedWorld, null));

        Path oldWorld = this.directory.resolve("old-world");
        Files.createDirectories(oldWorld);
        Files.writeString(oldWorld.resolve(TerrainWorldRegistry.MARKER_FILENAME),
            "schema=1\nversion=99\nseed=37\nmin-y=-64\nheight=384\nsea-level=63\n");
        assertThrows(IllegalStateException.class, () -> TerrainWorldRegistry.resolve(oldWorld, null));
    }

    @Test
    void returnsNullForUnmarkedVanillaWorld() throws Exception {
        Path world = this.directory.resolve("vanilla-world");
        Files.createDirectories(world);

        assertNull(TerrainWorldRegistry.resolve(world, null));
    }

    private static void writeLegacyMarker(Path world) throws Exception {
        Files.createDirectories(world);
        Files.writeString(world.resolve(TerrainWorldRegistry.MARKER_FILENAME), LEGACY_MARKER);
    }

    private static WorldInfo worldInfo(long seed, int minHeight, int maxHeight) {
        WorldInfo info = mock(WorldInfo.class);
        when(info.getName()).thenReturn("test-world");
        when(info.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(info.getSeed()).thenReturn(seed);
        when(info.getMinHeight()).thenReturn(minHeight);
        when(info.getMaxHeight()).thenReturn(maxHeight);
        return info;
    }
}
