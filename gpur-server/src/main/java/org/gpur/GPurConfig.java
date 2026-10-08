package org.gpur;

import com.google.common.base.Throwables;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class GPurConfig {
    private static final String HEADER = "This is the main configuration file for GPur.\n"
        + "GPur adds directional chunk preloading and experimental Vulkan-backed\n"
        + "exact numeric compute and opt-in custom terrain on top of Purpur 26.2.\n"
        + "\n"
        + "If Vulkan initialization fails or no compatible GPU is present, GPur\n"
        + "will automatically continue in CPU mode.\n";
    private static final int CURRENT_CONFIG_VERSION = 2;

    private static File configFile;
    public static YamlConfiguration config;

    public static int version;

    public static boolean gpuAccelerationEnabled = true;
    public static boolean terrainGpuEnabled = true;
    public static boolean legacyTerrainBatchEnabled = false;
    public static int gpuExecutionContexts = 5;
    public static int gpuQueueThreshold = 10;
    public static int gpuUsageFallback = 90;
    public static int gpuBatchSize = 16;
    public static int gpuMinBatchSize = 4;
    public static int gpuMaxQueueWaitMillis = 8;
    public static boolean terrainGpuHeavyLoadOnly = true;
    public static int terrainGpuHeavyLoadMinActiveNoiseTasks = 8;
    public static int terrainGpuHeavyLoadMinPendingRequests = 8;
    public static int terrainGpuHeavyLoadMinOldestPendingMillis = 20;
    public static int terrainGpuHeavyLoadSustainMillis = 40;

    public static boolean preloadingEnabled = true;
    public static int baseLookahead = 3;
    public static double elytraLookaheadFactor = 0.6D;
    public static double elytraAngleDegrees = 60.0D;
    public static double elytraLookDirectionBlend = 0.35D;
    public static int elytraTurnLookaheadBoost = 6;
    public static double elytraTurnAngleBoostDegrees = 18.0D;
    public static boolean sharedPreloadingEnabled = true;
    public static int sharedPreloadingMaxDistance = 12;
    public static double sharedPreloadingDirectionDotThreshold = 0.85D;
    public static int sharedPreloadingAnchorExpiryMillis = 1500;
    public static int sharedPreloadingMaxAnchors = 3;
    public static boolean preloadRetentionEnabled = true;
    public static int preloadRetentionMillis = 1750;
    public static double preloadRetentionDirectionDotThreshold = 0.82D;
    public static boolean elytraSendBurstEnabled = true;
    public static int elytraSendBurstExtraChunks = 8;
    public static boolean elytraThroughputBoostEnabled = false;
    public static double elytraBoostStartSpeed = 18.0D;
    public static double elytraBoostFullSpeed = 32.0D;
    public static double elytraLoadRateMultiplier = 2.0D;
    public static double elytraGenRateMultiplier = 2.0D;
    public static double elytraSendRateMultiplier = 2.5D;
    public static double elytraConcurrentLoadsMultiplier = 2.0D;
    public static double elytraConcurrentGeneratesMultiplier = 2.0D;
    public static boolean throughputStabilizationEnabled = true;
    public static int throughputTargetQueueSize = 10;
    public static double throughputMaxRateMultiplier = 1.35D;
    public static double throughputMaxConcurrentMultiplier = 1.5D;
    public static double throughputMaxScanMultiplier = 1.75D;
    public static int throughputSendBurstExtraChunks = 6;
    public static boolean turboModeEnabled = false;
    public static int turboExtraLookahead = 6;
    public static double turboLoadRateMultiplier = 1.7D;
    public static double turboGenRateMultiplier = 1.9D;
    public static double turboSendRateMultiplier = 2.1D;
    public static double turboConcurrentLoadsMultiplier = 1.7D;
    public static double turboConcurrentGeneratesMultiplier = 1.8D;
    public static int turboSendBurstExtraChunks = 12;
    public static int turboGpuQueueThreshold = 1;
    public static int turboGpuMinBatchSize = 1;
    public static boolean reloadOptimizationEnabled = true;
    public static int reloadHotCacheTicks = 12;
    public static int reloadMaxRetainedChunks = 512;

    public static boolean antiXrayGpuEnabled = true;
    public static boolean antiXrayGpuAllowInexactResults = false;
    public static int antiXrayGpuMinSections = 1;
    public static int antiXrayGpuReservedContexts = 1;
    public static boolean structureScanGpuEnabled = true;
    public static int structureScanGpuMinCandidates = 16;
    public static boolean mobSpawnGpuEnabled = true;
    public static int mobSpawnGpuMinCandidates = 1;
    public static int mobSpawnGpuMinPlayers = 32;
    public static int mobSpawnGpuMinDistanceChecks = 128;

    public static GPurLogVerbosity terrainAssistLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity terrainBatchLogVerbosity = GPurLogVerbosity.OFF;
    public static GPurLogVerbosity antiXrayLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity structureScanLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity mobSpawnLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static boolean publicSafeLogging = true;

    public static boolean multiGpuEnabled = true;
    public static java.util.List<String> gpuDevices = java.util.List.of("auto");
    public static boolean gpuForce = false;
    public static int gpuTimeoutMillis = 100;
    public static int preloadMaxExtraDistance = 0;
    public static boolean playersGpuEnabled = true;
    public static int playersGpuMinPlayers = 128;

    public static boolean gpuCacheEnabled = true;
    public static boolean terrainCustomEnabled = true;
    public static boolean terrainAutoTune = true;
    public static int terrainMaxBatchChunks = 8;
    public static int terrainQueueCapacity = 128;
    public static int terrainBatchWaitMillis = 2;
    public static int terrainRequestTimeoutMillis = 250;
    public static int terrainParityInterval = 128;

    private GPurConfig() {
    }

    public static void init(final File configFile) {
        GPurConfig.configFile = configFile;
        config = new YamlConfiguration();

        try {
            config.load(configFile);
        } catch (final IOException ignored) {
        } catch (final InvalidConfigurationException ex) {
            logger().log(Level.SEVERE, "Could not load gpur.yml, please correct your syntax errors", ex);
            throw Throwables.propagate(ex);
        }

        config.options().header(HEADER);
        config.options().copyDefaults(true);

        version = getInt("config-version", CURRENT_CONFIG_VERSION);
        set("config-version", CURRENT_CONFIG_VERSION);

        // Change only the known v1 defaults; preserve explicit custom values.
        if (version < 2) {
            migrateDefault("gpu-offload.anti-xray.min-sections", 12, 1);
            migrateDefault("gpu-offload.mob-spawn.min-candidates", 32, 1);
        }
        multiGpuEnabled = getBoolean("gpu.multi-gpu.enabled", true);
        config.addDefault("gpu.multi-gpu.devices", java.util.List.of("auto"));
        gpuDevices = java.util.List.copyOf(config.getStringList("gpu.multi-gpu.devices"));
        gpuForce = getBoolean("gpu.force", false);
        gpuTimeoutMillis = clamp(getInt("gpu.timeout-ms", 100), 1, 2000, "gpu.timeout-ms");
        playersGpuEnabled = getBoolean("gpu-offload.players.enabled", true);
        playersGpuMinPlayers = clamp(getInt("gpu-offload.players.min-players", 128), 1, 16384, "gpu-offload.players.min-players");
        readGpuAcceleration();
        readCustomTerrain();
        readPreloading();
        preloadMaxExtraDistance = clamp(getInt("chunk-generation.preloading.max-extra-distance", 0), 0, 16, "chunk-generation.preloading.max-extra-distance");
        readGpuOffload();
        readLogging();
        if (antiXrayGpuAllowInexactResults) {
            logger().warning("GPur 26.2 requires exact Anti-Xray results; allow-inexact-results is no longer supported");
            antiXrayGpuAllowInexactResults = false;
            set("gpu-offload.anti-xray.allow-inexact-results", false);
        }
        version = CURRENT_CONFIG_VERSION;
        save();
    }

    private static void readGpuAcceleration() {
        gpuAccelerationEnabled = getBoolean("chunk-generation.gpu-acceleration.enabled", true);
        terrainGpuEnabled = getBoolean("chunk-generation.gpu-acceleration.terrain-enabled", true);
        legacyTerrainBatchEnabled = getBoolean(
            "chunk-generation.gpu-acceleration.legacy-terrain-batch-enabled",
            false
        );
        gpuExecutionContexts = clamp(
            getInt("chunk-generation.gpu-acceleration.execution-contexts", 5),
            2,
            16,
            "chunk-generation.gpu-acceleration.execution-contexts"
        );
        gpuQueueThreshold = Math.max(1, getInt("chunk-generation.gpu-acceleration.queue-threshold", 10));
        gpuUsageFallback = clamp(
            getInt("chunk-generation.gpu-acceleration.gpu-usage-fallback", 90),
            1,
            100,
            "chunk-generation.gpu-acceleration.gpu-usage-fallback"
        );
        gpuBatchSize = Math.max(1, getInt("chunk-generation.gpu-acceleration.batch-size", 16));
        gpuMinBatchSize = clamp(
            getInt("chunk-generation.gpu-acceleration.min-batch-size", 4),
            1,
            gpuBatchSize,
            "chunk-generation.gpu-acceleration.min-batch-size"
        );
        gpuMaxQueueWaitMillis = clamp(
            getInt("chunk-generation.gpu-acceleration.max-queue-wait-ms", 8),
            0,
            500,
            "chunk-generation.gpu-acceleration.max-queue-wait-ms"
        );
        terrainGpuHeavyLoadOnly = getBoolean(
            "chunk-generation.gpu-acceleration.terrain-heavy-load-only",
            true
        );
        terrainGpuHeavyLoadMinActiveNoiseTasks = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-active-noise-tasks",
                8
            ),
            1,
            128,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-active-noise-tasks"
        );
        terrainGpuHeavyLoadMinPendingRequests = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-pending-requests",
                8
            ),
            1,
            gpuBatchSize,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-pending-requests"
        );
        terrainGpuHeavyLoadMinOldestPendingMillis = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-oldest-pending-ms",
                20
            ),
            0,
            500,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-oldest-pending-ms"
        );
        terrainGpuHeavyLoadSustainMillis = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.sustain-ms",
                40
            ),
            0,
            1000,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.sustain-ms"
        );
    }

    private static void readCustomTerrain() {
        gpuCacheEnabled = getBoolean("gpu.cache.enabled", true);
        terrainCustomEnabled = getBoolean("chunk-generation.custom-terrain.enabled", true);
        terrainAutoTune = getBoolean("chunk-generation.custom-terrain.auto-tune", true);
        terrainMaxBatchChunks = clamp(getInt("chunk-generation.custom-terrain.max-batch-chunks", 8), 1, 8,
            "chunk-generation.custom-terrain.max-batch-chunks");
        terrainQueueCapacity = clamp(getInt("chunk-generation.custom-terrain.queue-capacity", 128), 1, 1024,
            "chunk-generation.custom-terrain.queue-capacity");
        terrainBatchWaitMillis = clamp(getInt("chunk-generation.custom-terrain.batch-wait-ms", 2), 0, 10,
            "chunk-generation.custom-terrain.batch-wait-ms");
        terrainRequestTimeoutMillis = clamp(getInt("chunk-generation.custom-terrain.request-timeout-ms", 250), 1, 5000,
            "chunk-generation.custom-terrain.request-timeout-ms");
        terrainParityInterval = clamp(getInt("chunk-generation.custom-terrain.parity-interval", 128), 1, 4096,
            "chunk-generation.custom-terrain.parity-interval");
    }

    public static java.nio.file.Path gpuCacheDirectory() {
        java.nio.file.Path parent = (configFile == null ? new File("gpur.yml") : configFile)
            .toPath().toAbsolutePath().normalize().getParent();
        return parent.resolve("cache").resolve("gpur-gpu");
    }

    private static void readPreloading() {
        preloadingEnabled = getBoolean("chunk-generation.preloading.enabled", true);
        baseLookahead = Math.max(1, getInt("chunk-generation.preloading.base-lookahead", 3));
        elytraLookaheadFactor = Math.max(0.0D, getDouble("chunk-generation.preloading.elytra-lookahead-factor", 0.6D));
        elytraAngleDegrees = clamp(
            getDouble("chunk-generation.preloading.elytra-angle-degrees", 60.0D),
            1.0D,
            89.0D,
            "chunk-generation.preloading.elytra-angle-degrees"
        );
        elytraLookDirectionBlend = clamp(
            getDouble("chunk-generation.preloading.elytra-look-direction-blend", 0.35D),
            0.0D,
            0.9D,
            "chunk-generation.preloading.elytra-look-direction-blend"
        );
        elytraTurnLookaheadBoost = clamp(
            getInt("chunk-generation.preloading.elytra-turn-lookahead-boost", 6),
            0,
            24,
            "chunk-generation.preloading.elytra-turn-lookahead-boost"
        );
        elytraTurnAngleBoostDegrees = clamp(
            getDouble("chunk-generation.preloading.elytra-turn-angle-boost-degrees", 18.0D),
            0.0D,
            30.0D,
            "chunk-generation.preloading.elytra-turn-angle-boost-degrees"
        );
        sharedPreloadingEnabled = getBoolean("chunk-generation.preloading.shared.enabled", true);
        sharedPreloadingMaxDistance = clamp(
            getInt("chunk-generation.preloading.shared.max-distance", 12),
            1,
            48,
            "chunk-generation.preloading.shared.max-distance"
        );
        sharedPreloadingDirectionDotThreshold = clamp(
            getDouble("chunk-generation.preloading.shared.direction-dot-threshold", 0.85D),
            0.1D,
            0.999D,
            "chunk-generation.preloading.shared.direction-dot-threshold"
        );
        sharedPreloadingAnchorExpiryMillis = clamp(
            getInt("chunk-generation.preloading.shared.anchor-expiry-ms", 1500),
            100,
            10000,
            "chunk-generation.preloading.shared.anchor-expiry-ms"
        );
        sharedPreloadingMaxAnchors = clamp(
            getInt("chunk-generation.preloading.shared.max-anchors", 3),
            1,
            16,
            "chunk-generation.preloading.shared.max-anchors"
        );
        preloadRetentionEnabled = getBoolean("chunk-generation.preloading.retention.enabled", true);
        preloadRetentionMillis = clamp(
            getInt("chunk-generation.preloading.retention.duration-ms", 1750),
            0,
            10000,
            "chunk-generation.preloading.retention.duration-ms"
        );
        preloadRetentionDirectionDotThreshold = clamp(
            getDouble("chunk-generation.preloading.retention.direction-dot-threshold", 0.82D),
            0.1D,
            0.999D,
            "chunk-generation.preloading.retention.direction-dot-threshold"
        );
        elytraSendBurstEnabled = getBoolean("chunk-generation.preloading.send-burst.enabled", true);
        elytraSendBurstExtraChunks = clamp(
            getInt("chunk-generation.preloading.send-burst.extra-chunks", 8),
            0,
            64,
            "chunk-generation.preloading.send-burst.extra-chunks"
        );
        elytraThroughputBoostEnabled = getBoolean("chunk-generation.preloading.elytra-throughput-boost.enabled", false);
        elytraBoostStartSpeed = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.start-speed", 18.0D),
            0.0D,
            80.0D,
            "chunk-generation.preloading.elytra-throughput-boost.start-speed"
        );
        elytraBoostFullSpeed = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.full-speed", 32.0D),
            Math.max(elytraBoostStartSpeed + 0.1D, 0.1D),
            120.0D,
            "chunk-generation.preloading.elytra-throughput-boost.full-speed"
        );
        elytraLoadRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.load-rate-multiplier", 2.0D),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.load-rate-multiplier"
        );
        elytraGenRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.generate-rate-multiplier", 2.0D),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.generate-rate-multiplier"
        );
        elytraSendRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.send-rate-multiplier", 2.5D),
            1.0D,
            8.0D,
            "chunk-generation.preloading.elytra-throughput-boost.send-rate-multiplier"
        );
        elytraConcurrentLoadsMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.concurrent-loads-multiplier", 2.0D),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.concurrent-loads-multiplier"
        );
        elytraConcurrentGeneratesMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.concurrent-generates-multiplier", 2.0D),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.concurrent-generates-multiplier"
        );
        throughputStabilizationEnabled = getBoolean(
            "chunk-generation.preloading.throughput-stabilization.enabled",
            true
        );
        throughputTargetQueueSize = clamp(
            getInt("chunk-generation.preloading.throughput-stabilization.target-queue-size", 10),
            2,
            128,
            "chunk-generation.preloading.throughput-stabilization.target-queue-size"
        );
        throughputMaxRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.throughput-stabilization.max-rate-multiplier", 1.35D),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-rate-multiplier"
        );
        throughputMaxConcurrentMultiplier = clamp(
            getDouble(
                "chunk-generation.preloading.throughput-stabilization.max-concurrent-multiplier",
                1.5D
            ),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-concurrent-multiplier"
        );
        throughputMaxScanMultiplier = clamp(
            getDouble("chunk-generation.preloading.throughput-stabilization.max-scan-multiplier", 1.75D),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-scan-multiplier"
        );
        throughputSendBurstExtraChunks = clamp(
            getInt(
                "chunk-generation.preloading.throughput-stabilization.send-burst-extra-chunks",
                6
            ),
            0,
            64,
            "chunk-generation.preloading.throughput-stabilization.send-burst-extra-chunks"
        );
        turboModeEnabled = getBoolean("chunk-generation.turbo-mode.enabled", false);
        turboExtraLookahead = clamp(
            getInt("chunk-generation.turbo-mode.extra-lookahead", 6),
            0,
            32,
            "chunk-generation.turbo-mode.extra-lookahead"
        );
        turboLoadRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.load-rate-multiplier", 1.7D),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.load-rate-multiplier"
        );
        turboGenRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.generate-rate-multiplier", 1.9D),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.generate-rate-multiplier"
        );
        turboSendRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.send-rate-multiplier", 2.1D),
            1.0D,
            10.0D,
            "chunk-generation.turbo-mode.send-rate-multiplier"
        );
        turboConcurrentLoadsMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.concurrent-loads-multiplier", 1.7D),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.concurrent-loads-multiplier"
        );
        turboConcurrentGeneratesMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.concurrent-generates-multiplier", 1.8D),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.concurrent-generates-multiplier"
        );
        turboSendBurstExtraChunks = clamp(
            getInt("chunk-generation.turbo-mode.send-burst-extra-chunks", 12),
            0,
            96,
            "chunk-generation.turbo-mode.send-burst-extra-chunks"
        );
        turboGpuQueueThreshold = clamp(
            getInt("chunk-generation.turbo-mode.gpu-queue-threshold", 1),
            1,
            16,
            "chunk-generation.turbo-mode.gpu-queue-threshold"
        );
        turboGpuMinBatchSize = clamp(
            getInt("chunk-generation.turbo-mode.gpu-min-batch-size", 1),
            1,
            gpuBatchSize,
            "chunk-generation.turbo-mode.gpu-min-batch-size"
        );
        reloadOptimizationEnabled = getBoolean("chunk-generation.reload-optimization.enabled", true);
        reloadHotCacheTicks = clamp(
            getInt("chunk-generation.reload-optimization.hot-cache-ticks", 12),
            0,
            600,
            "chunk-generation.reload-optimization.hot-cache-ticks"
        );
        reloadMaxRetainedChunks = clamp(
            getInt("chunk-generation.reload-optimization.max-retained-chunks", 512),
            1,
            16384,
            "chunk-generation.reload-optimization.max-retained-chunks"
        );
    }

    private static void readGpuOffload() {
        antiXrayGpuEnabled = getBoolean("gpu-offload.anti-xray.enabled", true);
        antiXrayGpuAllowInexactResults = getBoolean(
            "gpu-offload.anti-xray.allow-inexact-results",
            false
        );
        antiXrayGpuMinSections = clamp(
            getInt("gpu-offload.anti-xray.min-sections", 1),
            1,
            24,
            "gpu-offload.anti-xray.min-sections"
        );
        antiXrayGpuReservedContexts = clamp(
            getInt("gpu-offload.anti-xray.reserved-contexts", 1),
            0,
            Math.max(0, gpuExecutionContexts - 1),
            "gpu-offload.anti-xray.reserved-contexts"
        );
        structureScanGpuEnabled = getBoolean("gpu-offload.structure-scan.enabled", true);
        structureScanGpuMinCandidates = clamp(
            getInt("gpu-offload.structure-scan.min-candidates", 16),
            1,
            512,
            "gpu-offload.structure-scan.min-candidates"
        );
        mobSpawnGpuEnabled = getBoolean("gpu-offload.mob-spawn.enabled", true);
        mobSpawnGpuMinCandidates = clamp(
            getInt("gpu-offload.mob-spawn.min-candidates", 1),
            1,
            512,
            "gpu-offload.mob-spawn.min-candidates"
        );
        mobSpawnGpuMinPlayers = clamp(
            getInt("gpu-offload.mob-spawn.min-players", 32),
            1,
            64,
            "gpu-offload.mob-spawn.min-players"
        );
        mobSpawnGpuMinDistanceChecks = clamp(
            getInt("gpu-offload.mob-spawn.min-distance-checks", 128),
            1,
            16384,
            "gpu-offload.mob-spawn.min-distance-checks"
        );
    }

    private static void readLogging() {
        terrainAssistLogVerbosity = getLogVerbosity("logging.gpu.terrain-assist", GPurLogVerbosity.SUMMARY);
        terrainBatchLogVerbosity = getLogVerbosity("logging.gpu.terrain-batch", GPurLogVerbosity.OFF);
        antiXrayLogVerbosity = getLogVerbosity("logging.gpu.anti-xray", GPurLogVerbosity.SUMMARY);
        structureScanLogVerbosity = getLogVerbosity("logging.gpu.structure-scan", GPurLogVerbosity.SUMMARY);
        mobSpawnLogVerbosity = getLogVerbosity("logging.gpu.mob-spawn", GPurLogVerbosity.SUMMARY);
        publicSafeLogging = getBoolean("logging.public-safe", true);
    }

    private static void migrateDefault(final String path, final int oldValue, final int newValue) {
        if (config.isSet(path) && config.getInt(path) == oldValue) {
            config.set(path, newValue);
        }
    }

    private static int clamp(final int value, final int min, final int max, final String path) {
        if (value < min || value > max) {
            logger().warning(path + " must be between " + min + " and " + max + ", using nearest valid value");
        }
        final int normalized = Math.max(min, Math.min(max, value));
        config.set(path, normalized);
        return normalized;
    }

    private static double clamp(final double value, final double min, final double max, final String path) {
        if (!Double.isFinite(value)) {
            logger().warning(path + " must be finite, using " + min);
            config.set(path, min);
            return min;
        }
        if (value < min || value > max) {
            logger().warning(path + " must be between " + min + " and " + max + ", using nearest valid value");
        }
        double normalized = Math.max(min, Math.min(max, value));
        config.set(path, normalized);
        return normalized;
    }

    private static void set(final String path, final Object value) {
        config.addDefault(path, value);
        config.set(path, value);
    }

    private static boolean getBoolean(final String path, final boolean defaultValue) {
        config.addDefault(path, defaultValue);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static double getDouble(final String path, final double defaultValue) {
        config.addDefault(path, defaultValue);
        return config.getDouble(path, config.getDouble(path));
    }

    private static GPurLogVerbosity getLogVerbosity(final String path, final GPurLogVerbosity defaultValue) {
        config.addDefault(path, defaultValue.name().toLowerCase(java.util.Locale.ROOT));
        final String configured = config.getString(path, defaultValue.name().toLowerCase(java.util.Locale.ROOT));
        final GPurLogVerbosity resolved = GPurLogVerbosity.fromString(configured);
        config.set(path, resolved.name().toLowerCase(java.util.Locale.ROOT));
        return resolved;
    }

    private static int getInt(final String path, final int defaultValue) {
        config.addDefault(path, defaultValue);
        return config.getInt(path, config.getInt(path));
    }

    private static void save() {
        try {
            config.save(configFile);
        } catch (final IOException ex) {
            logger().log(Level.SEVERE, "Could not save " + configFile, ex);
        }
    }

    private static Logger logger() {
        try {
            return Bukkit.getLogger();
        } catch (final Throwable ignored) {
            return Logger.getLogger("GPur");
        }
    }
}
