package org.gpur.terrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.generator.WorldInfo;

/** Restores legacy GPur terrain worlds and rejects new opt-ins. */
public final class TerrainWorldRegistry {
    public static final String GENERATOR_ID = "gpur:terrain-v1";
    public static final String MARKER_FILENAME = "gpur-terrain.properties";
    private static final int MARKER_SCHEMA = 1;
    private static final int TERRAIN_VERSION = TerrainRules.VERSION;
    private static final Set<String> MARKER_KEYS = Set.of("schema", "version", "seed", "min-y", "height", "sea-level");
    private static final ConcurrentHashMap<Path, GPurTerrainGenerator> GENERATORS = new ConcurrentHashMap<>();
    private static final Set<Path> LEGACY_RESTORE_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = Logger.getLogger("GPur");

    private TerrainWorldRegistry() {}

    /**
     * Restore a world when its persistent marker proves that it was already generated with
     * GPur terrain. New GPur terrain opt-ins are retired; unmarked worlds use vanilla terrain
     * unless their operator selects another supported generator.
     *
     * @return the legacy generator for a marked world, or {@code null} for an unmarked vanilla world
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
            GPurTerrainGenerator generator = GENERATORS.computeIfAbsent(directory, ignored -> new GPurTerrainGenerator(directory, spec));
            if (LEGACY_RESTORE_WARNINGS.add(directory)) {
                LOGGER.warning("Restoring legacy GPur terrain v1 for marked world " + directory
                    + " to keep future chunks consistent with its existing terrain. New gpur:terrain-v1 opt-ins are retired.");
            }
            return generator;
        }

        if (!GENERATOR_ID.equals(configuredGenerator)) {
            return null;
        }
        throw new IllegalStateException("The new-world generator opt-in '" + GENERATOR_ID + "' is retired. Remove this generator entry to use the original vanilla terrain generator, or restore the original vanilla world's name in server.properties. To use a new vanilla world, choose a new world name without a custom generator. Existing world directories are left untouched.");
    }

    static TerrainWorldSpec validateOrCreate(Path worldDirectory, TerrainWorldSpec requested) {
        Path marker = worldDirectory.resolve(MARKER_FILENAME);
        GPurTerrainGenerator generator = GENERATORS.get(worldDirectory);
        if (generator == null) {
            throw new IllegalStateException("Cannot initialize retired GPur terrain for unregistered world " + worldDirectory);
        }
        synchronized (generator) {
            if (!Files.isRegularFile(marker)) {
                throw new IllegalStateException("Cannot initialize GPur terrain v1 for " + worldDirectory
                    + " because its legacy marker is missing; refusing to create terrain that could be mixed with vanilla chunks.");
            }
            TerrainWorldSpec stored = readMarker(marker);
            stored.requireMatches(requested, worldDirectory);
            return stored;
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
