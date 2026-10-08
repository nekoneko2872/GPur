package org.gpur.terrain;

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
    @TempDir
    Path directory;

    @Test
    void writesPermanentMarkerAndRestoresGeneratorWhenConfigurationIsRemoved() throws Exception {
        Path world = this.directory.resolve("new-world");
        Files.createDirectories(world);

        GPurTerrainGenerator optedIn = TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID);
        assertNotNull(optedIn);
        optedIn.validateWorldInfo(worldInfo(37L, -64, 320));

        Path marker = world.resolve(TerrainWorldRegistry.MARKER_FILENAME);
        assertTrue(Files.isRegularFile(marker));
        String contents = Files.readString(marker);
        assertTrue(contents.contains("schema=1\n"));
        assertTrue(contents.contains("version=1\n"));
        assertTrue(contents.contains("seed=37\n"));
        assertTrue(contents.contains("min-y=-64\n"));
        assertTrue(contents.contains("height=384\n"));
        assertTrue(contents.contains("sea-level=63\n"));

        assertSame(optedIn, TerrainWorldRegistry.resolve(world, null));
        assertNull(TerrainWorldRegistry.resolve(this.directory.resolve("vanilla-world"), null));
    }

    @Test
    void rejectsSeedOrHeightChangesForMarkedWorld() throws Exception {
        Path world = this.directory.resolve("immutable-world");
        Files.createDirectories(world);
        GPurTerrainGenerator generator = TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID);
        generator.validateWorldInfo(worldInfo(37L, -64, 320));

        assertThrows(IllegalStateException.class, () -> generator.validateWorldInfo(worldInfo(38L, -64, 320)));
        assertThrows(IllegalStateException.class, () -> generator.validateWorldInfo(worldInfo(37L, -64, 384)));
    }

    @Test
    void rejectsPopulatedWorldOnFirstOptIn() throws Exception {
        Path world = this.directory.resolve("populated-world");
        Path region = world.resolve("DIM-1/region");
        Files.createDirectories(region);
        Files.write(region.resolve("r.0.0.mca"), new byte[] {0});

        assertThrows(IllegalStateException.class, () -> TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID));
    }

    @Test
    void failsClosedForMalformedOrUnsupportedMarkers() throws Exception {
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
    void rejectsChangingGeneratorOfMarkedWorld() throws Exception {
        Path world = this.directory.resolve("changed-generator-world");
        Files.createDirectories(world);
        GPurTerrainGenerator generator = TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID);
        generator.validateWorldInfo(worldInfo(37L, -64, 320));

        assertThrows(IllegalStateException.class, () -> TerrainWorldRegistry.resolve(world, "some-plugin:generator"));
    }

    @Test
    void customTerrainDisableBlocksNewOptInButStillRestoresMarkedWorld() throws Exception {
        Path world = this.directory.resolve("previously-marked-world");
        Files.createDirectories(world);
        GPurTerrainGenerator generator = TerrainWorldRegistry.resolve(world, TerrainWorldRegistry.GENERATOR_ID);
        generator.validateWorldInfo(worldInfo(37L, -64, 320));

        boolean previous = GPurConfig.terrainCustomEnabled;
        try {
            GPurConfig.terrainCustomEnabled = false;
            assertSame(generator, TerrainWorldRegistry.resolve(world, null));
            assertThrows(IllegalStateException.class,
                () -> TerrainWorldRegistry.resolve(this.directory.resolve("disabled-new-world"), TerrainWorldRegistry.GENERATOR_ID));
        } finally {
            GPurConfig.terrainCustomEnabled = previous;
        }
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
