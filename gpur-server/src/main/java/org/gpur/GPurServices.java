package org.gpur;

import java.io.File;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.gpur.compute.ComputeService;
import org.gpur.preload.GPurSharedPreloadService;

public final class GPurServices {
    private static final GPurSharedPreloadService PRELOAD = new GPurSharedPreloadService();
    private static volatile ComputeService compute;

    private GPurServices() {}

    public static ComputeService compute() { return compute; }
    public static GPurSharedPreloadService preload() { return PRELOAD; }

    public static synchronized void reload(MinecraftServer server) {
        GPurConfig.init((File)server.options.valueOf("gpur-settings"));
        ComputeService replacement = new ComputeService(Logger.getLogger("GPur"));
        ComputeService previous = compute;
        compute = replacement;
        PRELOAD.reset();
        if (previous != null) previous.close();
    }

    public static synchronized void close() {
        ComputeService previous = compute;
        compute = null;
        if (previous != null) previous.close();
        PRELOAD.reset();
    }
}
