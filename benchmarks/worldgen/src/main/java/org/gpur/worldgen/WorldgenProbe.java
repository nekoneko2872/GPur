package org.gpur.worldgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Validation-only deterministic normal-world generation probe.
 *
 * It requests chunks through a bounded 64-request Bukkit async window, saves,
 * and emits an inventory/report. It never edits blocks or invokes a custom generator.
 */
public final class WorldgenProbe extends JavaPlugin implements CommandExecutor, Listener {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MARKER = ".gpurbench-worldgen";
    private static final int REQUEST_WINDOW = 64;
    private static final int LIGHT_SCAN_WINDOW = 64;
    private static final int LIGHT_POLL_TICKS = 5;
    private static final long LIGHT_TIMEOUT_NANOS = 120_000_000_000L;
    private Run active;
    private TickMetrics tickMetrics;
    private long runSequence;

    @Override
    public void onEnable() {
        try {
            Path cwd = Path.of("").toRealPath();
            Path container = Bukkit.getWorldContainer().toPath().toRealPath();
            if (!isValidationPath(cwd) || !isValidationPath(container)) {
                throw new IllegalStateException("requires cwd and world container beneath a validation directory; cwd="
                    + cwd + " worldContainer=" + container);
            }
            if (getCommand("gpurbench") == null) throw new IllegalStateException("gpurbench command is missing");
            this.tickMetrics = new TickMetrics();
            getCommand("gpurbench").setExecutor(this);
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("GPURWGEN_READY cwd=" + cwd + " container=" + container);
        } catch (Exception error) {
            getLogger().severe("GPURWGEN_INVALID " + error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private static boolean isValidationPath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.equals("c:/gpur-validation-20261009") || normalized.startsWith("c:/gpur-validation-20261009/")) return true;
        for (Path part : path) if (part.toString().equalsIgnoreCase("validation")) return true;
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 4 && args[0].equalsIgnoreCase("terrain") && args[1].equalsIgnoreCase("status")) {
            try {
                writeWorldgenStatus(sender, args[2], args[3]);
            } catch (Exception error) {
                getLogger().severe("GPURWGEN_STATUS_ERROR " + error);
                sender.sendMessage("GPURWGEN_STATUS_ERROR reason=" + error.getMessage());
            }
            return true;
        }
        if (args.length != 7 || !args[0].equalsIgnoreCase("terrain")) {
            sender.sendMessage("Usage: gpurbench terrain <generate|reload> <label> <seed> <centerChunkX> <centerChunkZ> <radius> | status <label> <stage>");
            return true;
        }
        if (active != null) {
            sender.sendMessage("GPURWGEN_ERROR busy=true");
            return true;
        }
        try {
            String phase = args[1].toLowerCase(Locale.ROOT);
            if (!phase.equals("generate") && !phase.equals("reload")) throw new IllegalArgumentException("phase must be generate or reload");
            String labelValue = args[2];
            if (!labelValue.matches("[A-Za-z0-9_-]{1,24}")) throw new IllegalArgumentException("label must be 1..24 ASCII letters, digits, _ or -");
            long seed = Long.parseLong(args[3]);
            int centerX = integer(args[4], "centerChunkX");
            int centerZ = integer(args[5], "centerChunkZ");
            int radius = Integer.parseInt(args[6]);
            if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be 1..64");
            if (Math.abs((long)centerX) + radius > 1_875_000 || Math.abs((long)centerZ) + radius > 1_875_000) {
                throw new IllegalArgumentException("chunk range exceeds world-border safety bound");
            }
            begin(sender, phase, labelValue, seed, centerX, centerZ, radius);
        } catch (Exception error) {
            getLogger().warning("GPURWGEN_ERROR " + error);
            sender.sendMessage("GPURWGEN_ERROR reason=" + error.getMessage());
        }
        return true;
    }

    /** Writes the production ComputeService counters without compiling this validation plugin against it. */
    private void writeWorldgenStatus(CommandSender sender, String label, String stage) throws Exception {
        if (!label.matches("[A-Za-z0-9_-]{1,24}") || !stage.matches("[A-Za-z0-9_-]{1,24}")) {
            throw new IllegalArgumentException("status label and stage must be 1..24 ASCII letters, digits, _ or -");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("label", label);
        report.put("stage", stage);
        report.put("captured_utc", Instant.now().toString());
        Map<String, Object> boundaries = new LinkedHashMap<>();
        boundaries.put("gpu_acceleration_enabled", configBoolean("gpuAccelerationEnabled"));
        boundaries.put("terrain_gpu_enabled", configBoolean("terrainGpuEnabled"));
        boundaries.put("vanilla_terrain_enabled", configBoolean("vanillaTerrainEnabled"));
        boundaries.put("noise_batches_enabled", configBoolean("vanillaNoiseGpuEnabled"));
        boundaries.put("aquifer_ranking_enabled", configBoolean("vanillaAquiferGpuEnabled"));
        report.put("configured_boundaries", boundaries);

        Object compute = null;
        Class<?> services = loadServerClass("org.gpur.GPurServices");
        if (services != null) compute = services.getMethod("compute").invoke(null);
        report.put("compute_service_present", compute != null);
        List<Map<String, Object>> devices = new ArrayList<>();
        List<Map<String, Object>> nativeBackends = new ArrayList<>();
        Map<String, Object> fallbacks = new LinkedHashMap<>();
        long nativeInFlightBatches = 0;
        long nativeQueueDepth = 0;
        if (compute != null) {
            Method eligible = compute.getClass().getMethod("worldgenEligible", int.class);
            Method fallbackCount = compute.getClass().getMethod("worldgenFallbacks", int.class);
            fallbacks.put("5", fallbackCount.invoke(compute, 5));
            fallbacks.put("6", fallbackCount.invoke(compute, 6));
            List<?> worldgenStatuses = (List<?>)compute.getClass().getMethod("worldgenDevices").invoke(compute);
            Map<String, String> namesByUuid = new LinkedHashMap<>();
            for (Object status : worldgenStatuses) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("uuid", invoke(status, "uuid"));
                item.put("name", invoke(status, "name"));
                namesByUuid.putIfAbsent(String.valueOf(item.get("uuid")), String.valueOf(item.get("name")));
                item.put("workload", invoke(status, "workload"));
                item.put("dispatches", invoke(status, "dispatches"));
                item.put("acceptedBatches", invoke(status, "acceptedBatches"));
                item.put("computedValues", invoke(status, "computedValues"));
                item.put("consumedValues", invoke(status, "consumedValues"));
                item.put("paritySamples", invoke(status, "paritySamples"));
                item.put("averageDispatchNanos", invoke(status, "averageDispatchNanos"));
                item.put("boundaryEnabled", (Boolean)eligible.invoke(compute, ((Number)item.get("workload")).intValue()));
                devices.add(item);
            }
            Object vanillaStatus = compute.getClass().getMethod("vanillaTerrainStatus").invoke(compute);
            Map<String, Object> vanillaTerrain = new LinkedHashMap<>();
            for (String field : List.of("enabled", "verifyEveryBatch", "cpuFallbacks", "parityFailures", "mode", "asyncSubmit")) {
                vanillaTerrain.put(field, invoke(vanillaStatus, field));
            }
            List<Map<String, Object>> vanillaDevices = new ArrayList<>();
            for (Object vanillaDevice : (List<?>)invoke(vanillaStatus, "devices")) {
                Object backend = invoke(vanillaDevice, "backend");
                Map<String, Object> vanillaValues = new LinkedHashMap<>();
                String uuid = String.valueOf(invoke(vanillaDevice, "uuid"));
                vanillaValues.put("uuid", uuid);
                vanillaValues.put("name", namesByUuid.getOrDefault(uuid, "unknown"));
                for (String field : List.of("slabs", "values", "averageDispatchNanos", "maxDispatchNanos",
                        "paritySamples", "observedSlabs", "verificationNanos")) {
                    vanillaValues.put(field, invoke(vanillaDevice, field));
                }
                vanillaDevices.add(vanillaValues);
                if (backend == null) continue;
                Map<String, Object> nativeMetrics = new LinkedHashMap<>();
                nativeMetrics.put("uuid", uuid);
                nativeMetrics.put("name", namesByUuid.getOrDefault(uuid, "unknown"));
                for (String field : List.of("queueDepth", "inFlightBatches", "submittedJobs", "completedJobs",
                        "timedOutJobs", "failedJobs", "rejectedJobs", "available")) {
                    nativeMetrics.put(field, invoke(backend, field));
                }
                nativeInFlightBatches += ((Number)nativeMetrics.get("inFlightBatches")).longValue();
                nativeQueueDepth += ((Number)nativeMetrics.get("queueDepth")).longValue();
                nativeBackends.add(nativeMetrics);
            }
            vanillaTerrain.put("devices", vanillaDevices);
            report.put("vanilla_terrain", vanillaTerrain);
        }
        report.put("fallbacks_by_workload", fallbacks);
        report.put("devices", devices);
        report.put("native_backend_metrics", nativeBackends);
        report.put("native_inflight_gpu_batches", compute == null ? null : nativeInFlightBatches);
        report.put("native_gpu_queue_depth", compute == null ? null : nativeQueueDepth);
        Path output = getDataFolder().toPath().resolve("runs").resolve(label).resolve("worldgen-status-" + stage + ".json");
        Files.createDirectories(output.getParent());
        if (Files.exists(output)) throw new IllegalStateException("status report already exists: " + output);
        Files.writeString(output, JSON.toJson(report) + "\n");
        getLogger().info("GPURWGEN_STATUS_DONE label=" + label + " stage=" + stage + " report=" + output.toAbsolutePath());
        sender.sendMessage("GPURWGEN_STATUS_DONE label=" + label + " stage=" + stage + " report=" + output.toAbsolutePath());
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static Boolean configBoolean(String fieldName) throws Exception {
        Class<?> config = loadServerClass("org.gpur.GPurConfig");
        if (config == null) return null;
        Object value = config.getField(fieldName).get(null);
        return value instanceof Boolean flag ? flag : null;
    }

    private static Class<?> loadServerClass(String name) {
        ClassLoader[] loaders = {WorldgenProbe.class.getClassLoader(), Bukkit.class.getClassLoader(),
            Bukkit.getServer().getClass().getClassLoader()};
        for (ClassLoader loader : loaders) {
            try { return Class.forName(name, true, loader); }
            catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    private static int integer(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (Math.abs((long)parsed) > 1_875_000) throw new IllegalArgumentException(name + " exceeds world-border safety bound");
        return parsed;
    }

    private void begin(CommandSender sender, String phase, String label, long seed,
                       int centerX, int centerZ, int radius) throws Exception {
        String worldName = "gpurbench_wgen_" + label;
        World world = Bukkit.getWorld(worldName);
        Path expectedPath = findExistingWorldPath(worldName);
        if (phase.equals("generate")) {
            if (world != null || expectedPath != null) {
                throw new IllegalStateException("refusing existing test world " + worldName + " at " + expectedPath);
            }
            world = new WorldCreator(worldName).seed(seed).type(WorldType.NORMAL)
                .generateStructures(true).createWorld();
            if (world == null) throw new IllegalStateException("WorldCreator returned null");
            if (world.getSeed() != seed || world.getEnvironment() != World.Environment.NORMAL) {
                throw new IllegalStateException("new world seed or environment mismatch");
            }
            Path actual = world.getWorldPath().toRealPath();
            if (!isValidationPath(actual)) throw new IllegalStateException("unsafe generated world path " + actual);
            Files.writeString(actual.resolve(MARKER), "GPurWorldgenProbe\nseed=" + seed + "\nstructures=true\n");
        } else {
            if (world == null) {
                if (expectedPath == null || !Files.isRegularFile(expectedPath.resolve(MARKER))) {
                    throw new IllegalStateException("reload requires a marked saved world; no marker at " + expectedPath);
                }
                world = new WorldCreator(worldName).seed(seed).type(WorldType.NORMAL)
                    .generateStructures(true).createWorld();
            }
            if (world == null || world.getSeed() != seed || world.getEnvironment() != World.Environment.NORMAL) {
                throw new IllegalStateException("saved world seed or environment mismatch");
            }
            if (!Files.isRegularFile(world.getWorldPath().resolve(MARKER))) {
                throw new IllegalStateException("saved world marker missing: " + world.getWorldPath());
            }
        }

        world.setKeepSpawnInMemory(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(6000);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        World checkedWorld = world;
        List<Coordinate> coordinates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) coordinates.add(new Coordinate(x, z));
        }
        if (phase.equals("reload")) {
            for (Coordinate coordinate : coordinates) {
                if (!checkedWorld.isChunkGenerated(coordinate.x(), coordinate.z())) {
                    throw new IllegalStateException("reload would need generation at " + coordinate.x() + "," + coordinate.z());
                }
            }
        }
        Path output = getDataFolder().toPath().resolve("runs").resolve(label);
        Files.createDirectories(output);
        Path report = output.resolve(phase + ".json");
        if (Files.exists(report)) throw new IllegalStateException("report already exists; use a fresh server run directory: " + report);
        long runId = ++this.runSequence;
        Path runDirectory = output;
        active = new Run(runId, sender, phase, label, seed, centerX, centerZ, radius,
            checkedWorld, coordinates, report,
            runDirectory.resolve(phase + "-tick-samples.csv"),
            runDirectory.resolve(phase + "-tick-metrics.json"));
        writeWorldgenStatus(sender, label, "before-corpus-" + phase);
        this.tickMetrics.begin(runId, label, phase, runDirectory);
        active.startedNanos = System.nanoTime();
        active.startedUtc = Instant.now().toString();
        active.preGenerated = phase.equals("generate")
            ? coordinates.stream().filter(c -> checkedWorld.isChunkGenerated(c.x(), c.z())).count()
            : coordinates.size();
        getLogger().info("GPURWGEN_START phase=" + phase + " label=" + label + " world=" + worldName
            + " seed=" + seed + " chunks=" + coordinates.size() + " bounds="
            + (centerX - radius) + "," + (centerZ - radius) + ".." + (centerX + radius) + "," + (centerZ + radius));
        sender.sendMessage("GPURWGEN_START phase=" + phase + " label=" + label + " chunks=" + coordinates.size());
        requestWindow(active);
    }

    private Path findExistingWorldPath(String name) throws IOException {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path direct = container.resolve(name);
        if (Files.exists(direct)) return direct;
        for (World loaded : Bukkit.getWorlds()) {
            Path candidate = loaded.getWorldPath().resolve("dimensions").resolve("minecraft").resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        // The server has a small, isolated run folder. Search only a bounded depth
        // so 26.2's dimension directory layout is checked before WorldCreator runs.
        try (var paths = Files.walk(container, 6)) {
            return paths.filter(Files::isDirectory)
                .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(name))
                .findFirst().orElse(null);
        }
    }

    private void requestWindow(Run run) {
        if (active != run) return;
        if (run.completed == run.coordinates.size()) {
            if (run.finishScheduled) return;
            run.finishScheduled = true;
            Bukkit.getScheduler().runTaskLater(this, () -> beginLightingScan(run), 20L);
            return;
        }
        while (run.inFlight < REQUEST_WINDOW && run.next < run.coordinates.size()) {
            Coordinate coordinate = run.coordinates.get(run.next++);
            boolean generatedBeforeRequest = run.world.isChunkGenerated(coordinate.x(), coordinate.z());
            run.preGeneratedRequests += generatedBeforeRequest ? 1 : 0;
            run.inFlight++;
            try {
                run.world.getChunkAtAsync(coordinate.x(), coordinate.z(), run.phase.equals("generate"))
                    .whenComplete((chunk, failure) -> Bukkit.getScheduler().runTask(this, () -> {
                        if (active != run) return;
                        run.inFlight--;
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                        if (cause != null || chunk == null) {
                            fail(run, "chunk request failed at " + coordinate.x() + "," + coordinate.z()
                                + (cause == null ? "" : ": " + cause));
                            return;
                        }
                        if (chunk.getX() != coordinate.x() || chunk.getZ() != coordinate.z()) {
                            fail(run, "chunk callback coordinate mismatch at " + coordinate.x() + "," + coordinate.z());
                            return;
                        }
                        run.completed++;
                        if (run.completed % 64 == 0 || run.completed == run.coordinates.size()) {
                            getLogger().info("GPURWGEN_PROGRESS phase=" + run.phase + " label=" + run.label
                                + " completed=" + run.completed + "/" + run.coordinates.size());
                        }
                        requestWindow(run);
                    }));
            } catch (Exception failure) {
                run.inFlight--;
                fail(run, "chunk request threw at " + coordinate.x() + "," + coordinate.z() + ": " + failure);
                return;
            }
        }
    }

    private void beginLightingScan(Run run) {
        if (active != run || run.lightingStarted) return;
        run.lightingStarted = true;
        run.lightingStartedNanos = System.nanoTime();
        getLogger().info("GPURWGEN_LIGHT_START label=" + run.label + " chunks=" + run.coordinates.size()
            + " status=isLightCorrect-and-persisted-at-least-LIGHT");
        requestLightingWindow(run);
    }

    private void requestLightingWindow(Run run) {
        if (active != run || run.lightingComplete) return;
        while (run.lightInFlight < LIGHT_SCAN_WINDOW && run.lightNext < run.coordinates.size()) {
            Coordinate coordinate = run.coordinates.get(run.lightNext++);
            run.lightInFlight++;
            long requestStarted = System.nanoTime();
            try {
                run.world.getChunkAtAsync(coordinate.x(), coordinate.z(), false).whenComplete((chunk, failure) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (active != run) return;
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                        if (cause != null || chunk == null) {
                            fail(run, "lighting check could not load chunk " + coordinate.x() + "," + coordinate.z()
                                + (cause == null ? "" : ": " + cause));
                            return;
                        }
                        if (!run.world.addPluginChunkTicket(coordinate.x(), coordinate.z(), this)) {
                            fail(run, "could not hold chunk ticket while checking lighting at "
                                + coordinate.x() + "," + coordinate.z());
                            return;
                        }
                        run.lightTickets.add(coordinate);
                        pollLighting(run, coordinate, chunk, requestStarted);
                    }));
            } catch (Exception failure) {
                run.lightInFlight--;
                fail(run, "lighting check request threw at " + coordinate.x() + "," + coordinate.z() + ": " + failure);
                return;
            }
        }
        if (run.lightCompleted == run.coordinates.size() && run.lightInFlight == 0) {
            run.lightingComplete = true;
            run.lightingFinishedNanos = System.nanoTime();
            getLogger().info("GPURWGEN_LIGHT_DONE label=" + run.label + " complete=" + run.lightCompleted
                + "/" + run.coordinates.size() + " wait_ms="
                + ((run.lightingFinishedNanos - run.lightingStartedNanos) / 1_000_000L));
            Bukkit.getScheduler().runTaskLater(this, () -> finish(run), 1L);
        }
    }

    private void pollLighting(Run run, Coordinate coordinate, org.bukkit.Chunk chunk, long requestStarted) {
        if (active != run) return;
        try {
            if (isFullyLit(chunk)) {
                releaseLightTicket(run, coordinate);
                run.lightInFlight--;
                run.lightCompleted++;
                if (run.lightCompleted % 64 == 0 || run.lightCompleted == run.coordinates.size()) {
                    getLogger().info("GPURWGEN_LIGHT_PROGRESS label=" + run.label + " complete="
                        + run.lightCompleted + "/" + run.coordinates.size());
                }
                requestLightingWindow(run);
                return;
            }
        } catch (Exception failure) {
            fail(run, "could not read light completion state at " + coordinate.x() + "," + coordinate.z()
                + ": " + failure);
            return;
        }
        if (System.nanoTime() - requestStarted >= LIGHT_TIMEOUT_NANOS) {
            fail(run, "lighting timeout at " + coordinate.x() + "," + coordinate.z()
                + " after " + (LIGHT_TIMEOUT_NANOS / 1_000_000L) + "ms; complete="
                + run.lightCompleted + "/" + run.coordinates.size());
            return;
        }
        Bukkit.getScheduler().runTaskLater(this,
            () -> pollLighting(run, coordinate, chunk, requestStarted), LIGHT_POLL_TICKS);
    }

    private static boolean isFullyLit(org.bukkit.Chunk chunk) throws Exception {
        Object handle = chunk.getClass().getMethod("getHandle").invoke(chunk);
        boolean lightCorrect = (Boolean)invoke(handle, "isLightCorrect");
        Object persistedStatus = invoke(handle, "getPersistedStatus");
        Class<?> statusClass = loadServerClass("net.minecraft.world.level.chunk.status.ChunkStatus");
        if (statusClass == null) throw new IllegalStateException("ChunkStatus class is unavailable");
        Object lightStatus = statusClass.getField("LIGHT").get(null);
        boolean statusLit = (Boolean)statusClass.getMethod("isOrAfter", statusClass)
            .invoke(persistedStatus, lightStatus);
        return lightCorrect && statusLit;
    }

    private void releaseLightTicket(Run run, Coordinate coordinate) {
        if (run.lightTickets.contains(coordinate)) {
            run.world.removePluginChunkTicket(coordinate.x(), coordinate.z(), this);
            run.lightTickets.remove(coordinate);
        }
    }

    private void releaseAllLightTickets(Run run) {
        // Try every ticket even if Paper rejects one removal. In particular, one broken
        // chunk must not strand the rest of the bounded lighting window on a failed run.
        for (Coordinate coordinate : List.copyOf(run.lightTickets)) {
            try {
                releaseLightTicket(run, coordinate);
            } catch (RuntimeException failure) {
                getLogger().severe("GPURWGEN_TICKET_RELEASE_ERROR label=" + run.label + " chunk="
                    + coordinate.x() + "," + coordinate.z() + " reason=" + failure);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void tickStart(ServerTickStartEvent event) {
        if (this.tickMetrics == null) return;
        Run run = this.active;
        this.tickMetrics.tickStart(event, run == null ? 0 : run.id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void tickEnd(ServerTickEndEvent event) {
        if (this.tickMetrics != null) this.tickMetrics.tickEnd(event);
    }

    private void finish(Run run) {
        if (active != run) return;
        if (!run.lightingComplete) {
            fail(run, "save was requested before the required lighting scan completed");
            return;
        }
        try {
            long saveStart = System.nanoTime();
            run.world.save();
            long saveMillis = (System.nanoTime() - saveStart) / 1_000_000L;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", 1);
            result.put("phase", run.phase);
            result.put("label", run.label);
            result.put("world", run.world.getName());
            result.put("world_path", run.world.getWorldPath().toRealPath().toString());
            result.put("seed", run.seed);
            result.put("environment", run.world.getEnvironment().name());
            result.put("world_type", "NORMAL");
            result.put("structures", true);
            result.put("center_chunk", List.of(run.centerX, run.centerZ));
            result.put("radius_chunks", run.radius);
            result.put("bounds_chunk_inclusive", List.of(run.centerX - run.radius, run.centerZ - run.radius,
                run.centerX + run.radius, run.centerZ + run.radius));
            result.put("request_order", "chunkX ascending; chunkZ ascending within each X");
            result.put("requested_chunks", run.coordinates.size());
            result.put("completed_chunks", run.completed);
            result.put("lighting_complete", run.lightCompleted == run.coordinates.size());
            result.put("lighting_complete_chunks", run.lightCompleted);
            result.put("lighting_wait_ms", (run.lightingFinishedNanos - run.lightingStartedNanos) / 1_000_000L);
            result.put("lighting_status_required", "isLightCorrect and persisted ChunkStatus at least LIGHT");
            result.put("pre_generated_before_command", run.preGenerated);
            result.put("pre_generated_before_each_request", run.preGeneratedRequests);
            result.put("min_y", run.world.getMinHeight());
            result.put("max_y_exclusive", run.world.getMaxHeight());
            result.put("online_players", Bukkit.getOnlinePlayers().size());
            result.put("living_entities_in_probe_world", run.world.getLivingEntities().size());
            result.put("natural_mob_spawning", false);
            result.put("daylight_cycle", false);
            result.put("started_utc", run.startedUtc);
            result.put("finished_utc", Instant.now().toString());
            result.put("request_elapsed_ms", (System.nanoTime() - run.startedNanos) / 1_000_000L);
            result.put("save_call_ms", saveMillis);
            result.put("save_note", "snapshot only after graceful server shutdown; save_call_ms is the explicit World.save call");
            result.put("tick_metrics_csv", run.tickMetricsCsv.toAbsolutePath().toString());
            result.put("tick_metrics_report", run.tickMetricsReport.toAbsolutePath().toString());
            Path temporary = run.report.resolveSibling(run.report.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(result) + "\n");
            try {
                Files.move(temporary, run.report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, run.report, StandardCopyOption.REPLACE_EXISTING);
            }
            getLogger().info("GPURWGEN_DONE phase=" + run.phase + " label=" + run.label
                + " chunks=" + run.completed + " request_ms=" + result.get("request_elapsed_ms")
                + " save_ms=" + saveMillis + " report=" + run.report.toAbsolutePath());
            run.sender.sendMessage("GPURWGEN_DONE phase=" + run.phase + " label=" + run.label
                + " chunks=" + run.completed + " report=" + run.report.toAbsolutePath());
            this.tickMetrics.end(run.id, true, null);
            releaseAllLightTickets(run);
            active = null;
        } catch (Exception failure) {
            fail(run, "save/report failure: " + failure);
        }
    }

    private void fail(Run run, String reason) {
        if (active != run) return;
        getLogger().severe("GPURWGEN_ERROR phase=" + run.phase + " label=" + run.label + " reason=" + reason);
        run.sender.sendMessage("GPURWGEN_ERROR phase=" + run.phase + " label=" + run.label + " reason=" + reason);
        if (this.tickMetrics != null) this.tickMetrics.end(run.id, false, reason);
        releaseAllLightTickets(run);
        active = null;
    }

    @Override
    public void onDisable() {
        if (active != null) fail(active, "plugin disabled while a phase was active");
        if (this.tickMetrics != null) this.tickMetrics.close();
    }

    private record Coordinate(int x, int z) {}

    private static final class Run {
        final long id;
        final CommandSender sender;
        final String phase, label;
        final long seed;
        final int centerX, centerZ, radius;
        final World world;
        final List<Coordinate> coordinates;
        final Path report;
        final Path tickMetricsCsv;
        final Path tickMetricsReport;
        int next, completed, inFlight;
        int lightNext, lightCompleted, lightInFlight;
        long lightingStartedNanos, lightingFinishedNanos;
        boolean lightingStarted, lightingComplete;
        final Set<Coordinate> lightTickets = new HashSet<>();
        boolean finishScheduled;
        long preGenerated, preGeneratedRequests, startedNanos;
        String startedUtc;

        Run(long id, CommandSender sender, String phase, String label, long seed, int centerX, int centerZ,
            int radius, World world, List<Coordinate> coordinates, Path report,
            Path tickMetricsCsv, Path tickMetricsReport) {
            this.id = id;
            this.sender = sender;
            this.phase = phase;
            this.label = label;
            this.seed = seed;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.world = world;
            this.coordinates = coordinates;
            this.report = report;
            this.tickMetricsCsv = tickMetricsCsv;
            this.tickMetricsReport = tickMetricsReport;
        }
    }
}
