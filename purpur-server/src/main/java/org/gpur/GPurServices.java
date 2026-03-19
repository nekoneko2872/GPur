package org.gpur;

import net.minecraft.server.MinecraftServer;
import org.gpur.generation.GPurChunkGenerationManager;
import org.gpur.preload.GPurSharedPreloadService;
import org.gpur.reload.GPurChunkReloadOptimizer;

public final class GPurServices {
    private static final GPurChunkGenerationManager GENERATION_MANAGER = new GPurChunkGenerationManager();
    private static final GPurSharedPreloadService PRELOAD_SERVICE = new GPurSharedPreloadService();
    private static final GPurChunkReloadOptimizer RELOAD_OPTIMIZER = new GPurChunkReloadOptimizer();

    private GPurServices() {
    }

    public static GPurChunkGenerationManager generation() {
        return GENERATION_MANAGER;
    }

    public static GPurSharedPreloadService preload() {
        return PRELOAD_SERVICE;
    }

    public static GPurChunkReloadOptimizer reloads() {
        return RELOAD_OPTIMIZER;
    }

    public static void reload(final MinecraftServer server) {
        PRELOAD_SERVICE.reset();
        RELOAD_OPTIMIZER.reload();
        GENERATION_MANAGER.reload(server);
    }
}
