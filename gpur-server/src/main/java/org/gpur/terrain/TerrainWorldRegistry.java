package org.gpur.terrain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.generator.WorldInfo;
import org.gpur.GPurConfig;

/** Resolves explicit custom-terrain opt-ins and restores worlds marked for GPur terrain. */
public final class TerrainWorldRegistry {
    public static final String GENERATOR_ID = "gpur:terrain-v1";
    public static final String MARKER_FILENAME = "gpur-terrain.properties";
    private static final int MARKER_SCHEMA = 1;
    private static final int TERRAIN_VERSION = TerrainRules.VERSION;
    private static final Set<String> MARKER_KEYS = Set.of("schema", "version", "seed", "min-y", "height", "sea-level");
    private static final ConcurrentHashMap<Path, GPurTerrainGenerator> GENERATORS = new ConcurrentHashMap<>();

    private TerrainWorldRegistry() {}

    /**
     * Resolve a world when it explicitly selects GPur terrain or when its persistent marker
     * proves that it was already generated with GPur terrain.
     *
     * @return the generator for the world, or {@code null} when the world is not opted in
     */
    public static GPurTerrainGenerator resolve(Path worldDirectory, String configuredGenerator) {
        Path directory = worldDirectory.toAbsolutePath().normalize();
        Path marker = directory.resolve(MARKER_FILENAME);
        boolean hasMarker = Files.exists(marker);
        if (configuredGenerator != null && configuredGenerator.startsWith("gpur:") && !GENERATOR_ID.equals(configuredGenerator)) {
            throw new IllegalStateException("Unknown GPur generator '" + configuredGenerator + "'; expected " + GENERATOR_ID);
        }

        if (hasMarker) {
            TerrainWorldSpec spec = readMarker(marker);
            if (configuredGenerator != null && !GENERATOR_ID.equals(configuredGenerator)) {
                throw new IllegalStateException("World " + directory + " is marked for GPur terrain v1, but its configured generator was changed to '" + configuredGenerator + "'. Remove the conflicting generator setting or restore " + GENERATOR_ID + ".");
            }
            return GENERATORS.computeIfAbsent(directory, ignored -> new GPurTerrainGenerator(directory, spec));
        }

        if (!GENERATOR_ID.equals(configuredGenerator)) {
            return null;
        }
        if (!GPurConfig.terrainCustomEnabled) {
            throw new IllegalStateException("Cannot opt in new world " + directory + " to " + GENERATOR_ID
                + " because chunk-generation.custom-terrain.enabled is false.");
        }
        if (containsExistingChunks(directory)) {
            throw new IllegalStateException("Cannot enable " + GENERATOR_ID + " for populated world " + directory + "; existing chunk data was found. Create a new empty world to use GPur terrain.");
        }
        return GENERATORS.computeIfAbsent(directory, ignored -> new GPurTerrainGenerator(directory, null));
    }

    static TerrainWorldSpec validateOrCreate(Path worldDirectory, TerrainWorldSpec requested) {
        Path marker = worldDirectory.resolve(MARKER_FILENAME);
        synchronized (GENERATORS.computeIfAbsent(worldDirectory, ignored -> new GPurTerrainGenerator(worldDirectory, null))) {
            if (Files.exists(marker)) {
                TerrainWorldSpec stored = readMarker(marker);
                stored.requireMatches(requested, worldDirectory);
                return stored;
            }
            if (containsExistingChunks(worldDirectory)) {
                throw new IllegalStateException("Cannot initialize " + GENERATOR_ID + " for populated world " + worldDirectory + "; existing chunk data was found.");
            }
            writeMarkerAtomically(marker, requested);
            return requested;
        }
    }

    private static boolean containsExistingChunks(Path worldDirectory) {
        if (!Files.isDirectory(worldDirectory)) {
            return false;
        }
        try (var files = Files.walk(worldDirectory)) {
            return files.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                String name = path.getFileName().toString();
                return name.endsWith(".mca") || name.endsWith(".mcc");
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect world data before enabling " + GENERATOR_ID + " in " + worldDirectory, exception);
        }
    }

    private static TerrainWorldSpec readMarker(Path marker) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1 || line.indexOf('=', separator + 1) >= 0) {
                    throw new IllegalStateException("Malformed GPur terrain marker " + marker);
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!MARKER_KEYS.contains(key) || values.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException("Malformed GPur terrain marker " + marker + ": unknown or duplicate key '" + key + "'");
                }
            }
            if (!values.keySet().equals(MARKER_KEYS)) {
                throw new IllegalStateException("Malformed GPur terrain marker " + marker + ": expected exactly " + MARKER_KEYS);
            }
            int schema = parseInt(values, "schema", marker);
            int version = parseInt(values, "version", marker);
            if (schema != MARKER_SCHEMA) {
                throw new IllegalStateException("Unsupported GPur terrain marker schema " + schema + " in " + marker + "; expected " + MARKER_SCHEMA);
            }
            if (version != TERRAIN_VERSION) {
                throw new IllegalStateException("Unsupported GPur terrain version " + version + " in " + marker + "; expected " + TERRAIN_VERSION);
            }
            TerrainWorldSpec spec = new TerrainWorldSpec(
                parseLong(values, "seed", marker),
                parseInt(values, "min-y", marker),
                parseInt(values, "height", marker),
                parseInt(values, "sea-level", marker)
            );
            spec.validate(marker.toString());
            return spec;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read GPur terrain marker " + marker, exception);
        }
    }

    private static int parseInt(Map<String, String> values, String key, Path marker) {
        try {
            return Integer.parseInt(values.get(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Malformed GPur terrain marker " + marker + ": invalid integer for " + key, exception);
        }
    }

    private static long parseLong(Map<String, String> values, String key, Path marker) {
        try {
            return Long.parseLong(values.get(key));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Malformed GPur terrain marker " + marker + ": invalid long for " + key, exception);
        }
    }

    private static void writeMarkerAtomically(Path marker, TerrainWorldSpec spec) {
        try {
            Files.createDirectories(marker.getParent());
            String contents = "schema=" + MARKER_SCHEMA + "\n"
                + "version=" + TERRAIN_VERSION + "\n"
                + "seed=" + spec.seed() + "\n"
                + "min-y=" + spec.minY() + "\n"
                + "height=" + spec.height() + "\n"
                + "sea-level=" + spec.seaLevel() + "\n";
            Path temporary = Files.createTempFile(marker.getParent(), MARKER_FILENAME + ".", ".tmp");
            try {
                byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                try {
                    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IllegalStateException("Filesystem does not support atomic GPur terrain marker writes in " + marker.getParent(), exception);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not atomically write GPur terrain marker " + marker, exception);
        }
    }

    static int seaLevelFor(WorldInfo worldInfo) {
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();
        if (maxY <= minY) {
            throw new IllegalStateException("Invalid world height range [" + minY + ", " + maxY + ") for " + worldInfo.getName());
        }
        return Math.max(minY, Math.min(maxY - 1, 63));
    }

    static TerrainWorldSpec specFor(WorldInfo worldInfo) {
        if (worldInfo.getEnvironment() != World.Environment.NORMAL) {
            throw new IllegalStateException(GENERATOR_ID + " supports NORMAL worlds only; world '" + worldInfo.getName() + "' uses " + worldInfo.getEnvironment());
        }
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();
        if (maxY <= minY) {
            throw new IllegalStateException("Invalid world height range [" + minY + ", " + maxY + ") for " + worldInfo.getName());
        }
        long height = (long) maxY - minY;
        if (height > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid world height range [" + minY + ", " + maxY + ") for " + worldInfo.getName());
        }
        TerrainWorldSpec requested = new TerrainWorldSpec(worldInfo.getSeed(), minY, (int) height, seaLevelFor(worldInfo));
        requested.validate(worldInfo.getName());
        return requested;
    }

    record TerrainWorldSpec(long seed, int minY, int height, int seaLevel) {
        void validate(String where) {
            if (height <= 0 || (long) minY + height > Integer.MAX_VALUE || (long) minY + height <= Integer.MIN_VALUE) {
                throw new IllegalStateException("Invalid GPur terrain height in " + where);
            }
            if (seaLevel < minY || (long) seaLevel >= (long) minY + height) {
                throw new IllegalStateException("Invalid GPur terrain sea level in " + where);
            }
            try {
                TerrainRules.input(this.seed, this.minY, this.height, this.seaLevel, 0, 0);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("GPur terrain marker contains unsupported world dimensions in " + where, exception);
            }
        }

        void requireMatches(TerrainWorldSpec requested, Path worldDirectory) {
            if (this.seed != requested.seed || this.minY != requested.minY || this.height != requested.height || this.seaLevel != requested.seaLevel) {
                throw new IllegalStateException("World " + worldDirectory + " was created with GPur terrain spec seed="
                    + this.seed + ", minY=" + this.minY + ", height=" + this.height + ", seaLevel=" + this.seaLevel
                    + ", but current world info is seed=" + requested.seed + ", minY=" + requested.minY
                    + ", height=" + requested.height + ", seaLevel=" + requested.seaLevel
                    + ". GPur terrain settings are permanent for this world.");
            }
        }
    }
}
