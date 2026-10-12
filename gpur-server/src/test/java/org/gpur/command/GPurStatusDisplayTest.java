package org.gpur.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.gpur.compute.ComputeService;
import org.gpur.terrain.TerrainScheduler;
import org.junit.jupiter.api.Test;

class GPurStatusDisplayTest {
    @Test void worldgenRowsDistinguishCompletedGpuWorkFromUsedResultsAndDisabledBoundaries() {
        var device = new ComputeService.DeviceWorkloadStatus("uuid", "NVIDIA GeForce GTX 1080", 1, true, 0,
            0, 0, 0, 0, 0, 0, 0);
        var snapshot = new ComputeService.StatusSnapshot(false, false, 0, 0, 0, 0, List.of(device));
        var worldgen = new GPurStatusDisplay.WorldgenOverview(List.of(
            new ComputeService.WorldgenDeviceStatus("uuid", device.name(), 5, 8, 8, 1_024, 0, 8, 500_000),
            new ComputeService.WorldgenDeviceStatus("uuid", device.name(), 6, 4, 4, 1_024, 1_024, 4, 500_000),
            new ComputeService.WorldgenDeviceStatus("uuid", device.name(), 7, 56, 56, 9_800, 9_800, 56, 900_000)
        ), java.util.Set.of(5, 7), "strict");
        var options = new GPurStatusDisplay.Options(true, false, true, true, true, 0, false);
        String output = GPurStatusDisplay.render(snapshot, options, true, null, null, worldgen).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(output.contains("Noise leaves: Computed (not consumed) | 0 values used"));
        assertTrue(output.contains("Aquifer ranking: CPU (disabled in config) | 1,024 values used"));
        assertTrue(output.contains("Corner density graph: GPU results used | 9,800 values used"));
        assertTrue(output.contains("Accepted batches / computed values: 56 / 9,800"));
        assertTrue(output.contains("GPU query results: 0"), "query totals do not silently include worldgen work");
    }

    @Test void retiredCustomWorldsAreClearlyCpuOnlyRegardlessOfHistoricalGpuCounters() {
        var device = new ComputeService.DeviceWorkloadStatus("uuid", "NVIDIA GeForce GTX 1080", 1, true, 0,
            0, 0, 0, 0, 0, 0, 0);
        var snapshot = new ComputeService.StatusSnapshot(false, false, 0, 0, 0, 0, List.of(device));
        var terrain = new GPurStatusDisplay.TerrainOverview(1,
            new TerrainScheduler.Status(true, false, 2, 3, 15, 1, Map.of(TerrainScheduler.Fallback.TIMEOUT, 1L)),
            List.of(new ComputeService.TerrainDeviceStatus(0, "uuid", device.name(), true, 8, 16, 2, 500000, 900000, 1)));
        var options = new GPurStatusDisplay.Options(true, false, true, true, true, 0, false);
        String output = GPurStatusDisplay.render(snapshot, options, true, terrain).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(output.contains("Legacy custom terrain: 1 world(s) | CPU only (retired generator)"));
        assertFalse(output.contains("GPU enabled"));
        assertFalse(output.contains("16 chunks"));
        assertTrue(output.contains("CPU: Mob AI, redstone, plugins"));
    }

    @Test void vanillaStatusWorksWithoutCustomWorldsAndCountsSlabsRatherThanChunks() {
        var device = new ComputeService.DeviceWorkloadStatus("uuid", "NVIDIA GeForce GTX 1080", 1, true, 0,
            0, 0, 0, 0, 0, 0, 0);
        var snapshot = new ComputeService.StatusSnapshot(false, false, 0, 0, 0, 0, List.of(device));
        var vanilla = new ComputeService.VanillaStatus(true, true, 2, 0,
            List.of(new ComputeService.VanillaDeviceStatus("uuid", 8, 393216, 500000, 900000, 8)));
        var options = new GPurStatusDisplay.Options(true, false, true, true, true, 0, false);
        String output = GPurStatusDisplay.render(snapshot, options, true, null, vanilla).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(output.contains("Vanilla interpolation: GPU enabled | 8 slabs"));
        assertTrue(output.contains("Values / full parity checks: 393,216 / 8"));
        assertTrue(output.contains("Original world generator | vanilla rules preserved"));
        assertTrue(output.contains("Vanilla CPU parity: Every returned GPU slab"));
        assertTrue(output.contains("Vanilla mode: strict"));
        assertFalse(output.contains("Waiting for a custom world"));
        assertFalse(output.contains("8 chunks"));
    }
}
