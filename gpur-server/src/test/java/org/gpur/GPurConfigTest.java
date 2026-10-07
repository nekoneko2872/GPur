package org.gpur;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GPurConfigTest {
    @TempDir Path directory;

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
