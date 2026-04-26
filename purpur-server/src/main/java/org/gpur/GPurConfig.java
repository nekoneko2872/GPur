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
        + "chunk generation infrastructure on top of Purpur.\n"
        + "\n"
        + "If Vulkan initialization fails or no compatible GPU is present, GPur\n"
        + "will automatically continue in CPU mode.\n";
    private static final int CURRENT_CONFIG_VERSION = 1;

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
    public static boolean elytraThroughputBoostEnabled = true;
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
    public static int antiXrayGpuMinSections = 12;
    public static int antiXrayGpuReservedContexts = 1;
    public static boolean structureScanGpuEnabled = true;
    public static int structureScanGpuMinCandidates = 16;
    public static boolean mobSpawnGpuEnabled = true;
    public static int mobSpawnGpuMinCandidates = 32;
    public static int mobSpawnGpuMinPlayers = 2;
    public static int mobSpawnGpuMinDistanceChecks = 128;

    public static GPurLogVerbosity terrainAssistLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity terrainBatchLogVerbosity = GPurLogVerbosity.OFF;
    public static GPurLogVerbosity antiXrayLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity structureScanLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static GPurLogVerbosity mobSpawnLogVerbosity = GPurLogVerbosity.SUMMARY;
    public static boolean publicSafeLogging = true;

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

        readGpuAcceleration();
        readPreloading();
        readGpuOffload();
        readLogging();
        save();
    }

    private static void readGpuAcceleration() {
        gpuAccelerationEnabled = getBoolean("chunk-generation.gpu-acceleration.enabled", gpuAccelerationEnabled);
        terrainGpuEnabled = getBoolean("chunk-generation.gpu-acceleration.terrain-enabled", terrainGpuEnabled);
        legacyTerrainBatchEnabled = getBoolean(
            "chunk-generation.gpu-acceleration.legacy-terrain-batch-enabled",
            legacyTerrainBatchEnabled
        );
        gpuExecutionContexts = clamp(
            getInt("chunk-generation.gpu-acceleration.execution-contexts", gpuExecutionContexts),
            2,
            16,
            "chunk-generation.gpu-acceleration.execution-contexts"
        );
        gpuQueueThreshold = Math.max(1, getInt("chunk-generation.gpu-acceleration.queue-threshold", gpuQueueThreshold));
        gpuUsageFallback = clamp(
            getInt("chunk-generation.gpu-acceleration.gpu-usage-fallback", gpuUsageFallback),
            1,
            100,
            "chunk-generation.gpu-acceleration.gpu-usage-fallback"
        );
        gpuBatchSize = Math.max(1, getInt("chunk-generation.gpu-acceleration.batch-size", gpuBatchSize));
        gpuMinBatchSize = clamp(
            getInt("chunk-generation.gpu-acceleration.min-batch-size", gpuMinBatchSize),
            1,
            gpuBatchSize,
            "chunk-generation.gpu-acceleration.min-batch-size"
        );
        gpuMaxQueueWaitMillis = clamp(
            getInt("chunk-generation.gpu-acceleration.max-queue-wait-ms", gpuMaxQueueWaitMillis),
            0,
            500,
            "chunk-generation.gpu-acceleration.max-queue-wait-ms"
        );
        terrainGpuHeavyLoadOnly = getBoolean(
            "chunk-generation.gpu-acceleration.terrain-heavy-load-only",
            terrainGpuHeavyLoadOnly
        );
        terrainGpuHeavyLoadMinActiveNoiseTasks = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-active-noise-tasks",
                terrainGpuHeavyLoadMinActiveNoiseTasks
            ),
            1,
            128,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-active-noise-tasks"
        );
        terrainGpuHeavyLoadMinPendingRequests = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-pending-requests",
                terrainGpuHeavyLoadMinPendingRequests
            ),
            1,
            gpuBatchSize,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-pending-requests"
        );
        terrainGpuHeavyLoadMinOldestPendingMillis = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.min-oldest-pending-ms",
                terrainGpuHeavyLoadMinOldestPendingMillis
            ),
            0,
            500,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.min-oldest-pending-ms"
        );
        terrainGpuHeavyLoadSustainMillis = clamp(
            getInt(
                "chunk-generation.gpu-acceleration.terrain-heavy-load.sustain-ms",
                terrainGpuHeavyLoadSustainMillis
            ),
            0,
            1000,
            "chunk-generation.gpu-acceleration.terrain-heavy-load.sustain-ms"
        );
    }

    private static void readPreloading() {
        preloadingEnabled = getBoolean("chunk-generation.preloading.enabled", preloadingEnabled);
        baseLookahead = Math.max(1, getInt("chunk-generation.preloading.base-lookahead", baseLookahead));
        elytraLookaheadFactor = Math.max(0.0D, getDouble("chunk-generation.preloading.elytra-lookahead-factor", elytraLookaheadFactor));
        elytraAngleDegrees = clamp(
            getDouble("chunk-generation.preloading.elytra-angle-degrees", elytraAngleDegrees),
            1.0D,
            89.0D,
            "chunk-generation.preloading.elytra-angle-degrees"
        );
        elytraLookDirectionBlend = clamp(
            getDouble("chunk-generation.preloading.elytra-look-direction-blend", elytraLookDirectionBlend),
            0.0D,
            0.9D,
            "chunk-generation.preloading.elytra-look-direction-blend"
        );
        elytraTurnLookaheadBoost = clamp(
            getInt("chunk-generation.preloading.elytra-turn-lookahead-boost", elytraTurnLookaheadBoost),
            0,
            24,
            "chunk-generation.preloading.elytra-turn-lookahead-boost"
        );
        elytraTurnAngleBoostDegrees = clamp(
            getDouble("chunk-generation.preloading.elytra-turn-angle-boost-degrees", elytraTurnAngleBoostDegrees),
            0.0D,
            30.0D,
            "chunk-generation.preloading.elytra-turn-angle-boost-degrees"
        );
        sharedPreloadingEnabled = getBoolean("chunk-generation.preloading.shared.enabled", sharedPreloadingEnabled);
        sharedPreloadingMaxDistance = clamp(
            getInt("chunk-generation.preloading.shared.max-distance", sharedPreloadingMaxDistance),
            1,
            48,
            "chunk-generation.preloading.shared.max-distance"
        );
        sharedPreloadingDirectionDotThreshold = clamp(
            getDouble("chunk-generation.preloading.shared.direction-dot-threshold", sharedPreloadingDirectionDotThreshold),
            0.1D,
            0.999D,
            "chunk-generation.preloading.shared.direction-dot-threshold"
        );
        sharedPreloadingAnchorExpiryMillis = clamp(
            getInt("chunk-generation.preloading.shared.anchor-expiry-ms", sharedPreloadingAnchorExpiryMillis),
            100,
            10000,
            "chunk-generation.preloading.shared.anchor-expiry-ms"
        );
        sharedPreloadingMaxAnchors = clamp(
            getInt("chunk-generation.preloading.shared.max-anchors", sharedPreloadingMaxAnchors),
            1,
            16,
            "chunk-generation.preloading.shared.max-anchors"
        );
        preloadRetentionEnabled = getBoolean("chunk-generation.preloading.retention.enabled", preloadRetentionEnabled);
        preloadRetentionMillis = clamp(
            getInt("chunk-generation.preloading.retention.duration-ms", preloadRetentionMillis),
            0,
            10000,
            "chunk-generation.preloading.retention.duration-ms"
        );
        preloadRetentionDirectionDotThreshold = clamp(
            getDouble("chunk-generation.preloading.retention.direction-dot-threshold", preloadRetentionDirectionDotThreshold),
            0.1D,
            0.999D,
            "chunk-generation.preloading.retention.direction-dot-threshold"
        );
        elytraSendBurstEnabled = getBoolean("chunk-generation.preloading.send-burst.enabled", elytraSendBurstEnabled);
        elytraSendBurstExtraChunks = clamp(
            getInt("chunk-generation.preloading.send-burst.extra-chunks", elytraSendBurstExtraChunks),
            0,
            64,
            "chunk-generation.preloading.send-burst.extra-chunks"
        );
        elytraThroughputBoostEnabled = getBoolean("chunk-generation.preloading.elytra-throughput-boost.enabled", elytraThroughputBoostEnabled);
        elytraBoostStartSpeed = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.start-speed", elytraBoostStartSpeed),
            0.0D,
            80.0D,
            "chunk-generation.preloading.elytra-throughput-boost.start-speed"
        );
        elytraBoostFullSpeed = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.full-speed", elytraBoostFullSpeed),
            Math.max(elytraBoostStartSpeed + 0.1D, 0.1D),
            120.0D,
            "chunk-generation.preloading.elytra-throughput-boost.full-speed"
        );
        elytraLoadRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.load-rate-multiplier", elytraLoadRateMultiplier),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.load-rate-multiplier"
        );
        elytraGenRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.generate-rate-multiplier", elytraGenRateMultiplier),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.generate-rate-multiplier"
        );
        elytraSendRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.send-rate-multiplier", elytraSendRateMultiplier),
            1.0D,
            8.0D,
            "chunk-generation.preloading.elytra-throughput-boost.send-rate-multiplier"
        );
        elytraConcurrentLoadsMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.concurrent-loads-multiplier", elytraConcurrentLoadsMultiplier),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.concurrent-loads-multiplier"
        );
        elytraConcurrentGeneratesMultiplier = clamp(
            getDouble("chunk-generation.preloading.elytra-throughput-boost.concurrent-generates-multiplier", elytraConcurrentGeneratesMultiplier),
            1.0D,
            6.0D,
            "chunk-generation.preloading.elytra-throughput-boost.concurrent-generates-multiplier"
        );
        throughputStabilizationEnabled = getBoolean(
            "chunk-generation.preloading.throughput-stabilization.enabled",
            throughputStabilizationEnabled
        );
        throughputTargetQueueSize = clamp(
            getInt("chunk-generation.preloading.throughput-stabilization.target-queue-size", throughputTargetQueueSize),
            2,
            128,
            "chunk-generation.preloading.throughput-stabilization.target-queue-size"
        );
        throughputMaxRateMultiplier = clamp(
            getDouble("chunk-generation.preloading.throughput-stabilization.max-rate-multiplier", throughputMaxRateMultiplier),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-rate-multiplier"
        );
        throughputMaxConcurrentMultiplier = clamp(
            getDouble(
                "chunk-generation.preloading.throughput-stabilization.max-concurrent-multiplier",
                throughputMaxConcurrentMultiplier
            ),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-concurrent-multiplier"
        );
        throughputMaxScanMultiplier = clamp(
            getDouble("chunk-generation.preloading.throughput-stabilization.max-scan-multiplier", throughputMaxScanMultiplier),
            1.0D,
            4.0D,
            "chunk-generation.preloading.throughput-stabilization.max-scan-multiplier"
        );
        throughputSendBurstExtraChunks = clamp(
            getInt(
                "chunk-generation.preloading.throughput-stabilization.send-burst-extra-chunks",
                throughputSendBurstExtraChunks
            ),
            0,
            64,
            "chunk-generation.preloading.throughput-stabilization.send-burst-extra-chunks"
        );
        turboModeEnabled = getBoolean("chunk-generation.turbo-mode.enabled", turboModeEnabled);
        turboExtraLookahead = clamp(
            getInt("chunk-generation.turbo-mode.extra-lookahead", turboExtraLookahead),
            0,
            32,
            "chunk-generation.turbo-mode.extra-lookahead"
        );
        turboLoadRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.load-rate-multiplier", turboLoadRateMultiplier),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.load-rate-multiplier"
        );
        turboGenRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.generate-rate-multiplier", turboGenRateMultiplier),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.generate-rate-multiplier"
        );
        turboSendRateMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.send-rate-multiplier", turboSendRateMultiplier),
            1.0D,
            10.0D,
            "chunk-generation.turbo-mode.send-rate-multiplier"
        );
        turboConcurrentLoadsMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.concurrent-loads-multiplier", turboConcurrentLoadsMultiplier),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.concurrent-loads-multiplier"
        );
        turboConcurrentGeneratesMultiplier = clamp(
            getDouble("chunk-generation.turbo-mode.concurrent-generates-multiplier", turboConcurrentGeneratesMultiplier),
            1.0D,
            8.0D,
            "chunk-generation.turbo-mode.concurrent-generates-multiplier"
        );
        turboSendBurstExtraChunks = clamp(
            getInt("chunk-generation.turbo-mode.send-burst-extra-chunks", turboSendBurstExtraChunks),
            0,
            96,
            "chunk-generation.turbo-mode.send-burst-extra-chunks"
        );
        turboGpuQueueThreshold = clamp(
            getInt("chunk-generation.turbo-mode.gpu-queue-threshold", turboGpuQueueThreshold),
            1,
            16,
            "chunk-generation.turbo-mode.gpu-queue-threshold"
        );
        turboGpuMinBatchSize = clamp(
            getInt("chunk-generation.turbo-mode.gpu-min-batch-size", turboGpuMinBatchSize),
            1,
            gpuBatchSize,
            "chunk-generation.turbo-mode.gpu-min-batch-size"
        );
        reloadOptimizationEnabled = getBoolean("chunk-generation.reload-optimization.enabled", reloadOptimizationEnabled);
        reloadHotCacheTicks = clamp(
            getInt("chunk-generation.reload-optimization.hot-cache-ticks", reloadHotCacheTicks),
            0,
            600,
            "chunk-generation.reload-optimization.hot-cache-ticks"
        );
        reloadMaxRetainedChunks = clamp(
            getInt("chunk-generation.reload-optimization.max-retained-chunks", reloadMaxRetainedChunks),
            1,
            16384,
            "chunk-generation.reload-optimization.max-retained-chunks"
        );
    }

    private static void readGpuOffload() {
        antiXrayGpuEnabled = getBoolean("gpu-offload.anti-xray.enabled", antiXrayGpuEnabled);
        antiXrayGpuAllowInexactResults = getBoolean(
            "gpu-offload.anti-xray.allow-inexact-results",
            antiXrayGpuAllowInexactResults
        );
        antiXrayGpuMinSections = clamp(
            getInt("gpu-offload.anti-xray.min-sections", antiXrayGpuMinSections),
            1,
            24,
            "gpu-offload.anti-xray.min-sections"
        );
        antiXrayGpuReservedContexts = clamp(
            getInt("gpu-offload.anti-xray.reserved-contexts", antiXrayGpuReservedContexts),
            0,
            Math.max(0, gpuExecutionContexts - 1),
            "gpu-offload.anti-xray.reserved-contexts"
        );
        structureScanGpuEnabled = getBoolean("gpu-offload.structure-scan.enabled", structureScanGpuEnabled);
        structureScanGpuMinCandidates = clamp(
            getInt("gpu-offload.structure-scan.min-candidates", structureScanGpuMinCandidates),
            1,
            512,
            "gpu-offload.structure-scan.min-candidates"
        );
        mobSpawnGpuEnabled = getBoolean("gpu-offload.mob-spawn.enabled", mobSpawnGpuEnabled);
        mobSpawnGpuMinCandidates = clamp(
            getInt("gpu-offload.mob-spawn.min-candidates", mobSpawnGpuMinCandidates),
            1,
            512,
            "gpu-offload.mob-spawn.min-candidates"
        );
        mobSpawnGpuMinPlayers = clamp(
            getInt("gpu-offload.mob-spawn.min-players", mobSpawnGpuMinPlayers),
            1,
            64,
            "gpu-offload.mob-spawn.min-players"
        );
        mobSpawnGpuMinDistanceChecks = clamp(
            getInt("gpu-offload.mob-spawn.min-distance-checks", mobSpawnGpuMinDistanceChecks),
            1,
            16384,
            "gpu-offload.mob-spawn.min-distance-checks"
        );
    }

    private static void readLogging() {
        terrainAssistLogVerbosity = getLogVerbosity("logging.gpu.terrain-assist", terrainAssistLogVerbosity);
        terrainBatchLogVerbosity = getLogVerbosity("logging.gpu.terrain-batch", terrainBatchLogVerbosity);
        antiXrayLogVerbosity = getLogVerbosity("logging.gpu.anti-xray", antiXrayLogVerbosity);
        structureScanLogVerbosity = getLogVerbosity("logging.gpu.structure-scan", structureScanLogVerbosity);
        mobSpawnLogVerbosity = getLogVerbosity("logging.gpu.mob-spawn", mobSpawnLogVerbosity);
        publicSafeLogging = getBoolean("logging.public-safe", publicSafeLogging);
    }

    private static int clamp(final int value, final int min, final int max, final String path) {
        if (value < min || value > max) {
            logger().warning(path + " must be between " + min + " and " + max + ", using nearest valid value");
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(final double value, final double min, final double max, final String path) {
        if (value < min || value > max) {
            logger().warning(path + " must be between " + min + " and " + max + ", using nearest valid value");
        }
        return Math.max(min, Math.min(max, value));
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
