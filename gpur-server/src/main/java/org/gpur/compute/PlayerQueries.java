package org.gpur.compute;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import org.gpur.GPurConfig;
import org.gpur.GPurServices;

public final class PlayerQueries {
    public record Result(boolean applied, Player player) {}
    private static final Result FALLBACK = new Result(false, null);

    private PlayerQueries() {}

    public static Result tryNearest(EntityGetter world, double x, double y, double z, double range,
                                    Predicate<Entity> predicate, boolean spawning) {
        if (!(world instanceof ServerLevel)) return FALLBACK;
        // Plugin-supplied predicates may have side effects. Preserve their interleaved CPU evaluation.
        if (predicate != null && predicate != EntitySelector.NO_SPECTATORS
                && predicate != EntitySelector.NO_CREATIVE_OR_SPECTATOR
                && predicate != EntitySelector.PLAYER_AFFECTS_SPAWNING) return FALLBACK;
        int minimum = spawning ? GPurConfig.mobSpawnGpuMinPlayers : GPurConfig.playersGpuMinPlayers;
        if (spawning ? !GPurConfig.mobSpawnGpuEnabled || GPurConfig.mobSpawnGpuMinCandidates > 1 : !GPurConfig.playersGpuEnabled) return FALLBACK;
        ComputeService service = GPurServices.compute();
        List<? extends Player> players = world.players();
        if (service == null || players.size() < minimum || !service.eligible(1)) return FALLBACK;
        List<Player> eligible = new ArrayList<>(players.size());
        for (Player player : players) if (predicate == null || predicate.test(player)) eligible.add(player);
        if (eligible.isEmpty() || spawning && eligible.size() < GPurConfig.mobSpawnGpuMinDistanceChecks) return FALLBACK;
        double[] positions = new double[eligible.size() * 3];
        for (int i = 0; i < eligible.size(); i++) {
            Player player = eligible.get(i);
            positions[i * 3] = player.getX();
            positions[i * 3 + 1] = player.getY();
            positions[i * 3 + 2] = player.getZ();
        }
        int[] distances = service.tryCompute(ExactCompute.distances(x, y, z, positions));
        if (distances == null) return FALLBACK;
        double best = -1;
        Player result = null;
        // Keep strict comparisons, negative/unlimited range, list order, and first-player ties.
        for (int i = 0; i < eligible.size(); i++) {
            double distance = ExactCompute.getDouble(distances, i * 2);
            if ((range < 0 || distance < range * range) && (best == -1 || distance < best)) {
                best = distance;
                result = eligible.get(i);
            }
        }
        return new Result(true, result);
    }

    public static Player nearestSpawnPlayer(ServerLevel world, double x, double y, double z, boolean excludeCreative) {
        Predicate<Entity> predicate = excludeCreative ? EntitySelector.NO_CREATIVE_OR_SPECTATOR : EntitySelector.NO_SPECTATORS;
        Result result = tryNearest(world, x, y, z, -1, predicate, true);
        return result.applied() ? result.player() : world.gpurNearestPlayerCpu(x, y, z, -1, predicate);
    }
}
