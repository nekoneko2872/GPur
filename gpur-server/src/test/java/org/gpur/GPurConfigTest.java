package org.gpur;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GPurConfigTest {
    @TempDir Path directory;

    @Test void asyncTerrainModesOverrideLegacyFlagsAndClampMemoryAndQueueControls() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, """
            chunk-generation:
              vanilla-terrain:
                enabled: false
                mode: verified-exact
            gpu:
              scheduler:
                batch-max-jobs: 100
                queue-capacity: 0
                batch-window-us: -1
                buffer-budget-mib: 1000000
              verification:
                full-first-batches: 0
                sample-one-in: 0
            """);
        GPurConfig.init(config.toFile());
        assertTrue(GPurConfig.vanillaTerrainEnabled);
        assertFalse(GPurConfig.vanillaTerrainVerifyEveryBatch);
        assertEquals(org.gpur.compute.VanillaVerificationPolicy.Mode.VERIFIED_EXACT, GPurConfig.vanillaTerrainMode);
        assertEquals(16, GPurConfig.gpuBatchMaxJobs);
        assertEquals(1, GPurConfig.gpuAsyncQueueCapacity);
        assertEquals(0, GPurConfig.gpuBatchWaitMicros);
        assertEquals(512, GPurConfig.gpuBufferBudgetMiB);
        assertEquals(1, GPurConfig.gpuFullFirstBatches);
        assertEquals(1, GPurConfig.gpuVerificationSampleOneIn);
        Files.writeString(config, "chunk-generation:\n  vanilla-terrain:\n    mode: approximate\n    enabled: true\n");
        GPurConfig.init(config.toFile());
        assertFalse(GPurConfig.vanillaTerrainEnabled);
        assertEquals(org.gpur.compute.VanillaVerificationPolicy.Mode.DISABLED, GPurConfig.vanillaTerrainMode);
    }

    @Test void vanillaTerrainRequiresOptInAndKeepsStrictParityAndBoundedFlightDefaults() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, "config-version: 2\n");
        GPurConfig.init(config.toFile());
        assertFalse(GPurConfig.vanillaTerrainEnabled);
        assertTrue(GPurConfig.vanillaTerrainVerifyEveryBatch);
        assertEquals(16, GPurConfig.vanillaTerrainMaxInterpolators);
        assertEquals(1_048_576, GPurConfig.vanillaTerrainMaxSlabValues);
        assertEquals(1024, GPurConfig.vanillaTerrainMinValues);
        assertEquals(8, GPurConfig.elytraMaxExtraConcurrentGenerates);

        Files.writeString(config, """
            gpu:
              multi-gpu:
                enabled: false
                devices: [NVIDIA GeForce GTX 1080]
            chunk-generation:
              vanilla-terrain:
                enabled: true
                max-interpolators: 100
                max-slab-values: 1
                minimum-values: 100000000
                parity-interval: 0
              preloading:
                elytra-throughput-boost:
                  max-extra-concurrent-generates: -1
            """);
        GPurConfig.init(config.toFile());
        assertTrue(GPurConfig.vanillaTerrainEnabled);
        assertTrue(GPurConfig.vanillaTerrainVerifyEveryBatch);
        assertEquals(16, GPurConfig.vanillaTerrainMaxInterpolators);
        assertEquals(1024, GPurConfig.vanillaTerrainMaxSlabValues);
        assertEquals(1024, GPurConfig.vanillaTerrainMinValues);
        assertEquals(1, GPurConfig.vanillaTerrainParityInterval);
        assertEquals(0, GPurConfig.elytraMaxExtraConcurrentGenerates);
        assertEquals(java.util.List.of("NVIDIA GeForce GTX 1080"), GPurConfig.gpuDevices);
    }

    @Test void customTerrainDefaultsAndLimitsPersistWithoutChangingGpuSelection() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, """
            gpu:
              multi-gpu:
                enabled: false
                devices: [NVIDIA GeForce GTX 1080]
            chunk-generation:
              custom-terrain:
                max-batch-chunks: 100
                queue-capacity: 0
                request-timeout-ms: 0
                parity-interval: 0
            """);
        GPurConfig.init(config.toFile());
        assertEquals(java.util.List.of("NVIDIA GeForce GTX 1080"), GPurConfig.gpuDevices);
        assertFalse(GPurConfig.multiGpuEnabled);
        assertFalse(GPurConfig.terrainCustomEnabled);
        assertTrue(GPurConfig.terrainAutoTune);
        assertEquals(8, GPurConfig.terrainMaxBatchChunks);
        assertEquals(1, GPurConfig.terrainQueueCapacity);
        assertEquals(1, GPurConfig.terrainRequestTimeoutMillis);
        assertEquals(1, GPurConfig.terrainParityInterval);
        assertEquals(directory.resolve("cache/gpur-gpu").toAbsolutePath(), GPurConfig.gpuCacheDirectory());
        assertEquals(8, GPurConfig.config.getInt("chunk-generation.custom-terrain.max-batch-chunks"));
    }

    @Test void customTerrainDefaultsDisabledButPreservesExplicitLegacySetting() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, "config-version: 2\n");
        GPurConfig.init(config.toFile());
        assertFalse(GPurConfig.terrainCustomEnabled);
        assertFalse(GPurConfig.config.getBoolean("chunk-generation.custom-terrain.enabled"));

        Files.writeString(config, """
            config-version: 2
            chunk-generation:
              custom-terrain:
                enabled: true
            """);
        GPurConfig.init(config.toFile());
        assertTrue(GPurConfig.terrainCustomEnabled);
        assertTrue(GPurConfig.config.getBoolean("chunk-generation.custom-terrain.enabled"));
    }

    @Test void preloadingDefaultsPrioritizeExistingChunksWithoutIncreasingGenerationDemand() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, "config-version: 2\n");
        GPurConfig.init(config.toFile());
        assertTrue(GPurConfig.preloadingEnabled);
        assertEquals(0, GPurConfig.preloadMaxExtraDistance);
        assertFalse(GPurConfig.elytraThroughputBoostEnabled);

        Files.writeString(config, """
            config-version: 2
            chunk-generation:
              preloading:
                max-extra-distance: 3
                elytra-throughput-boost:
                  enabled: true
            """);
        GPurConfig.init(config.toFile());
        assertEquals(3, GPurConfig.preloadMaxExtraDistance);
        assertTrue(GPurConfig.elytraThroughputBoostEnabled);
    }

    @Test void migratesUnreachableLegacyDefaultsAndRetainsCustomSettings() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, """
            config-version: 1
            gpu-offload:
              anti-xray:
                min-sections: 12
              mob-spawn:
                min-candidates: 32
            chunk-generation:
              preloading:
                base-lookahead: 7
            gpu:
              multi-gpu:
                devices: [my-device-uuid]
            custom-plugin-value: keep
            """);
        GPurConfig.init(config.toFile());
        assertEquals(1, GPurConfig.antiXrayGpuMinSections);
        assertEquals(1, GPurConfig.mobSpawnGpuMinCandidates);
        assertEquals(7, GPurConfig.baseLookahead);
        assertEquals(java.util.List.of("my-device-uuid"), GPurConfig.gpuDevices);
        assertEquals("keep", GPurConfig.config.getString("custom-plugin-value"));
        assertEquals(2, GPurConfig.config.getInt("config-version"));
    }

    @Test void preservesV2CustomThresholdAndRestoresDefaultsOnReload() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, "config-version: 2\ngpu-offload:\n  anti-xray:\n    min-sections: 12\n");
        GPurConfig.init(config.toFile());
        assertEquals(12, GPurConfig.antiXrayGpuMinSections);
        Files.writeString(config, "config-version: 2\n");
        GPurConfig.init(config.toFile());
        assertEquals(1, GPurConfig.antiXrayGpuMinSections);
    }

    @Test void rejectsInexactAntiXrayOptInAndPersistsClampedContexts() throws Exception {
        Path config = directory.resolve("gpur.yml");
        Files.writeString(config, """
            gpu-offload:
              anti-xray:
                allow-inexact-results: true
            chunk-generation:
              gpu-acceleration:
                execution-contexts: 0
            """);
        GPurConfig.init(config.toFile());
        assertFalse(GPurConfig.antiXrayGpuAllowInexactResults);
        assertEquals(2, GPurConfig.gpuExecutionContexts);
        assertEquals(2, GPurConfig.config.getInt("chunk-generation.gpu-acceleration.execution-contexts"));
    }
}
