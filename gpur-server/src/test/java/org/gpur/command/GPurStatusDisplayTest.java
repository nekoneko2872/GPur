package org.gpur.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.gpur.compute.ComputeService;
import org.gpur.terrain.TerrainScheduler;
import org.junit.jupiter.api.Test;

class GPurStatusDisplayTest {
    @Test void terrainStatusDistinguishesDispatchedChunksFromReturnedPalettesAndCpuReasons() {
        var device = new ComputeService.DeviceWorkloadStatus("uuid", "NVIDIA GeForce GTX 1080", 1, true, 0,
            0, 0, 0, 0, 0, 0, 0);
        var snapshot = new ComputeService.StatusSnapshot(false, false, 0, 0, 0, 0, List.of(device));
        var terrain = new GPurStatusDisplay.TerrainOverview(1,
            new TerrainScheduler.Status(true, false, 2, 3, 15, 1, Map.of(TerrainScheduler.Fallback.TIMEOUT, 1L)),
            List.of(new ComputeService.TerrainDeviceStatus(0, "uuid", device.name(), true, 8, 16, 2, 500000, 900000, 1)));
        var options = new GPurStatusDisplay.Options(true, false, true, true, true, 0, false);
        String output = GPurStatusDisplay.render(snapshot, options, true, terrain).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(output.contains("Custom terrain: GPU enabled | 16 chunks"));
        assertTrue(output.contains("Custom terrain returned: GPU 15 | CPU 1 | queued 2"));
        assertTrue(output.contains("Terrain CPU timeout: 1"));
        assertTrue(output.contains("Terrain batch / parity samples: 8 / 1"));
        assertTrue(output.contains("CPU: Mob AI, redstone, plugins"));
    }
}
