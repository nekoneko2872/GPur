package org.gpur;

import java.io.File;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.gpur.compute.ComputeService;
import org.gpur.preload.GPurSharedPreloadService;
import org.gpur.terrain.TerrainScheduler;

public final class GPurServices {
    private static final GPurSharedPreloadService PRELOAD = new GPurSharedPreloadService();
    private static volatile ComputeService compute;
    private static volatile TerrainScheduler terrain;

    private GPurServices() {}

    public static ComputeService compute() { return compute; }
    public static TerrainScheduler terrain() { return terrain; }
    public static GPurSharedPreloadService preload() { return PRELOAD; }

    public static synchronized void reload(MinecraftServer server) {
        GPurConfig.init((File)server.options.valueOf("gpur-settings"));
        ComputeService previous = compute;
        TerrainScheduler previousTerrain = terrain;
        terrain = null;
        compute = null;
        if (previousTerrain != null) previousTerrain.close();
        if (previous != null) previous.close();
        ComputeService replacement = new ComputeService(Logger.getLogger("GPur"));
        TerrainScheduler replacementTerrain = new TerrainScheduler(replacement);
        compute = replacement;
        terrain = replacementTerrain;
        PRELOAD.reset();
    }

    public static synchronized void close() {
        ComputeService previous = compute;
        TerrainScheduler previousTerrain = terrain;
        compute = null;
        terrain = null;
        if (previousTerrain != null) previousTerrain.close();
        if (previous != null) previous.close();
        PRELOAD.reset();
    }
}
