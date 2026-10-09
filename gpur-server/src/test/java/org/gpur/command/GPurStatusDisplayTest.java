package org.gpur.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.gpur.compute.ComputeService;
import org.gpur.terrain.TerrainScheduler;
import org.junit.jupiter.api.Test;

class GPurStatusDisplayTest {
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
