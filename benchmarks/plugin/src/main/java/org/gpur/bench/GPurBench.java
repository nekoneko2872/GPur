package org.gpur.bench;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Cow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class GPurBench extends JavaPlugin implements Listener {
    private Measurements measurement;
    private NamespacedKey marker;
    private World world;
    private long worldSeed = 0x47507572L;
    private Random random = new Random(worldSeed);
    private final Map<String, BukkitTask> jobs = new HashMap<>();
    private final List<Block> circuitBlocks = new ArrayList<>();
    private final MovementTelemetry movementTelemetry = new MovementTelemetry();
    private long previousStartNs, tickStartNs, tickPhaseId, surveyTick = -20;
    private String tickPhase = "startup";
    private double intervalMs = -1, surveyMs;
    private int living, mobs, tickedMobs, syntheticMobs, chunks;
    private long redstoneEvents, spawnEvents, moveEvents, chunkLoadEvents, damageEvents;
    private String gpuStatus = "unavailable";
    private long gpuCompleted = -1, cpuFallbacks = -1;
    private Method gpuServiceMethod, gpuStatusMethod;
    private boolean gpuUnavailable;
    private boolean finished;
    private long jobSequence;
    private static final Pattern GPU_RESULTS = Pattern.compile("completed GPU results=(\\d+)");
    private static final Pattern CPU_FALLBACKS = Pattern.compile("CPU fallbacks=(\\d+)");

    @Override public void onEnable() {
        try {
            Path cwd = Path.of("").toRealPath();
            Path container = Bukkit.getWorldContainer().toPath().toRealPath();
            if (!isolated(cwd) || !isolated(container)) {
                throw new IllegalStateException("requires isolated cwd AND world container under a validation directory (candidate root C:/GPur-validation-20261009/validation); cwd=" + cwd + " worldContainer=" + container);
            }
            Path run = getDataFolder().toPath().resolve("runs").resolve(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now()));
            marker = new NamespacedKey(this, "synthetic");
            measurement = new Measurements(run, getLogger(), metadata());
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("GPURBENCH_READY output=" + run.toAbsolutePath() + " seed=" + worldSeed);
        } catch (Exception failure) {
            getLogger().severe("GPURBENCH_INVALID " + failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private static boolean isolated(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.equals("c:/gpur-validation-20261007") || normalized.startsWith("c:/gpur-validation-20261007/")) return true;
        for (Path part : path) if (part.toString().equalsIgnoreCase("validation")) return true;
        return false;
    }
    private Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server", Bukkit.getVersion()); result.put("bukkit", Bukkit.getBukkitVersion());
        result.put("java", System.getProperty("java.version")); result.put("java_vm", System.getProperty("java.vm.name"));
        result.put("jvm_args", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("max_heap_bytes", Runtime.getRuntime().maxMemory()); result.put("seed", worldSeed);
        result.put("online_players", Bukkit.getOnlinePlayers().size()); result.put("max_players", Bukkit.getMaxPlayers());
        result.put("bench_players", world == null ? 0 : world.getPlayers().size());
        result.put("configured_walking_fraction", movementTelemetry.walkingPercent < 0 ? null : movementTelemetry.walkingPercent / 100.0);
        result.put("configured_walking_percent", movementTelemetry.walkingPercent < 0 ? null : movementTelemetry.walkingPercent);
        result.put("world", world == null ? "none" : world.getName());
        if (world != null) { result.put("simulation_distance", world.getSimulationDistance()); result.put("view_distance", world.getViewDistance()); }
        result.put("gpu_status", gpuStatus); result.put("active_jobs", List.copyOf(jobs.keySet()));
        return result;
    }
    private void phase(String name) {
        closeMovementPhase();
        measurement.phase(name, metadata());
        movementTelemetry.begin(measurement.phaseId(), name, world);
    }
    private void closeMovementPhase() {
        Map<String, Object> summary = movementTelemetry.end(world);
        if (summary != null) measurement.event(Map.of("type", "movement_phase_summary", "summary", summary));
    }
    private void saveMovementTelemetry() {
        if (measurement == null || movementTelemetry.saved) return;
        closeMovementPhase();
        measurement.file("movement-telemetry.json", movementTelemetry.snapshot());
        movementTelemetry.saved = true;
    }
    private void requireWorld() {
        if (world == null) throw new IllegalStateException("create/select world first: /gpurbench world <label> <seed> [normal|flat]");
        if (!world.getName().startsWith("gpurbench_") || !isolated(world.getWorldFolder().toPath().toAbsolutePath())) throw new IllegalStateException("unsafe benchmark world");
    }
    private static int integer(String[] args, int at, int fallback, int min, int max) {
        int value = at < args.length ? Integer.parseInt(args[at]) : fallback;
        if (value < min || value > max) throw new IllegalArgumentException("argument " + at + " must be " + min + ".." + max);
        return value;
    }
    private static double decimal(String[] args, int at, double fallback, double min, double max) {
        double value = at < args.length ? Double.parseDouble(args[at]) : fallback;
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("argument " + at + " must be " + min + ".." + max);
        return value;
    }
    private void done(String command, String details) {
        getLogger().info("GPURBENCH_DONE command=" + command + " " + details);
        measurement.event(Map.of("type", "command_done", "command", command, "details", details, "epoch_ms", System.currentTimeMillis()));
    }
    private void start(String command, String details) {
        getLogger().info("GPURBENCH_START command=" + command + " job=" + (++jobSequence) + " " + details);
        measurement.event(Map.of("type", "command_start", "command", command, "details", details, "epoch_ms", System.currentTimeMillis()));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gpurbench.admin")) { sender.sendMessage("GPurBench requires gpurbench.admin"); return true; }
        if (measurement == null) { sender.sendMessage("GPURBENCH_INVALID measurement not initialized"); return true; }
        if (args.length == 0) {
            sender.sendMessage("gpurbench world <label> <seed> [normal|flat] | walking-percent <0..100> | phase <label> | prepare <radiusChunks> [perTick=1] [budgetMs=3] | place [spacing=96] | spawn <total> [radius=24] [perTick=25] | remove | travel <seconds> [speed=4.317] [east|west|north|south] | redstone <circuits> | clearredstone | status | finish");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            if (finished && !action.equals("status") && !action.equals("finish")) throw new IllegalStateException("measurement finished; restart for a new run");
            measurement.event(Map.of("type", "command", "arguments", List.of(args), "epoch_ms", System.currentTimeMillis()));
            switch (action) {
                case "world" -> createWorld(args);
                case "walking-percent" -> {
                    if (args.length != 2) throw new IllegalArgumentException("walking-percent requires an integer 0..100");
                    int percent = integer(args, 1, 100, 0, 100);
                    movementTelemetry.walkingPercent = percent;
                    done(action, "configured_walking_percent=" + percent + " configured_walking_fraction=" + (percent / 100.0));
                }
                case "phase" -> {
                    if (args.length != 2 || !args[1].matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("phase requires one label [A-Za-z0-9_.-], max80");
                    if (!jobs.isEmpty()) throw new IllegalStateException("wait for setup completion before changing phase; jobs=" + jobs.keySet());
                    phase(args[1]); done(action, "phase=" + args[1] + " players=" + Bukkit.getOnlinePlayers().size());
                }
                case "prepare" -> prepare(args);
                case "place" -> place(args);
                case "spawn" -> spawn(args);
                case "natural" -> {
                    requireWorld(); noJobs();
                    if (args.length != 2 || (!args[1].equals("true") && !args[1].equals("false"))) throw new IllegalArgumentException("natural true|false");
                    boolean enabled = Boolean.parseBoolean(args[1]);
                    world.setGameRule(GameRule.DO_MOB_SPAWNING, enabled);
                    done(action, "enabled=" + enabled);
                }
                case "remove" -> remove();
                case "removenatural" -> removeNatural();
                case "travel" -> travel(args);
                case "redstone" -> redstone(args);
                case "clearredstone" -> clearRedstone();
                case "status" -> {
                    sender.sendMessage("GPURBENCH_STATUS phase=" + measurement.phaseName() + " world=" + (world == null ? "none" : world.getName())
                        + " online=" + Bukkit.getOnlinePlayers().size() + " bench=" + (world == null ? 0 : world.getPlayers().size()) + " max=" + Bukkit.getMaxPlayers()
                        + " mobs=" + mobs + " ticked_chunk_mobs=" + tickedMobs + " synthetic=" + syntheticMobs + " chunks=" + chunks
                        + " jobs=" + jobs.keySet() + " writer_queue=" + measurement.backlog() + " writer_error=" + measurement.error() + " output=" + measurement.directory().toAbsolutePath());
                    sender.sendMessage("GPURBENCH_GPU " + gpuStatus); done(action, "ok=true");
                }
                case "finish" -> finish();
                default -> throw new IllegalArgumentException("unknown subcommand: " + action);
            }
        } catch (Exception failure) {
            getLogger().warning("GPURBENCH_ERROR command=" + action + " reason=" + failure.getMessage());
            sender.sendMessage("GPURBENCH_ERROR command=" + action + " reason=" + failure.getMessage());
            measurement.event(Map.of("type", "command_error", "command", action, "error", failure.toString(), "epoch_ms", System.currentTimeMillis()));
        }
        return true;
    }

    private void createWorld(String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("world <label> <seed> [normal|flat]");
        if (!jobs.isEmpty()) throw new IllegalStateException("wait for active jobs");
        if (!args[1].matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("world label must be simple ASCII up to48 characters");
        long seed = Long.parseLong(args[2]);
        String type = args.length > 3 ? args[3] : "normal";
        if (!type.equals("normal") && !type.equals("flat")) throw new IllegalArgumentException("world type must be normal or flat");
        String name = "gpurbench_" + args[1];
        Path folder = Bukkit.getWorldContainer().toPath().toRealPath().resolve(name).normalize();
        if (Files.exists(folder) && !Files.isRegularFile(folder.resolve(".gpurbench-synthetic"))) throw new IllegalStateException("refusing preexisting unmarked world " + folder);
        World existing = Bukkit.getWorld(name);
        if (existing != null && !Files.isRegularFile(existing.getWorldPath().resolve(".gpurbench-synthetic"))) throw new IllegalStateException("refusing preexisting unmarked dimension " + existing.getWorldPath());
        worldSeed = seed; random = new Random(seed);
        phase("setup-world"); start("world", "name=" + name + " seed=" + seed + " type=" + type);
        WorldCreator creator = new WorldCreator(name).seed(seed).type(type.equals("flat") ? WorldType.FLAT : WorldType.NORMAL).generateStructures(true);
        World created = creator.createWorld();
        if (created == null) throw new IllegalStateException("world creation returned null");
        if (created.getSeed() != seed) throw new IllegalStateException("existing world seed mismatch; use fresh world label");
        world = created;
        // 26.2 stores API worlds as dimensions under the level directory.
        Path actualFolder = world.getWorldPath().toRealPath();
        if (!isolated(actualFolder)) throw new IllegalStateException("unsafe actual world path " + actualFolder);
        Files.writeString(actualFolder.resolve(".gpurbench-synthetic"), "GPurBench synthetic world\nseed=" + seed + "\n");
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); world.setTime(6000);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false); world.setStorm(false); world.setThundering(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false); world.setGameRule(GameRule.MOB_GRIEFING, false);
        // Disable cramming damage so the synthetic count remains controlled without disabling AI.
        world.setGameRule(GameRule.MAX_ENTITY_CRAMMING, 0);
        measurement.file("world.json", Map.of("name", name, "seed", seed, "type", type, "natural_spawning", false, "structures", true));
        done("world", "name=" + name + " seed=" + seed + " type=" + type);
    }

    /** Synchronous paced generation: no hidden async work after the completion marker. */
    private void prepare(String[] args) {
        requireWorld(); noJobs();
        int radius = integer(args, 1, 4, 0, 128), perTick = integer(args, 2, 1, 1, 16);
        double budgetMs = decimal(args, 3, 3, .1, 40);
        List<int[]> targets = new ArrayList<>();
        List<Player> players = players();
        java.util.HashSet<Long> unique = new java.util.HashSet<>();
        List<Location> centers = players.isEmpty() ? List.of(world.getSpawnLocation()) : players.stream().map(Player::getLocation).toList();
        for (Location center : centers) for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            int cx = (center.getBlockX() >> 4) + x, cz = (center.getBlockZ() >> 4) + z;
            long key = ((long)cx << 32) ^ (cz & 0xffffffffL);
            if (unique.add(key)) targets.add(new int[]{cx, cz});
        }
        phase("pregeneration"); start("prepare", "chunks=" + targets.size() + " per_tick=" + perTick + " budget_ms=" + budgetMs);
        paced("prepare", targets.size(), perTick, budgetMs, index -> {
            int[] position = targets.get(index); var chunk = world.getChunkAt(position[0], position[1]);
            // Release pregeneration chunks to avoid accidentally changing subsequent ticking/loading behavior.
            if (world.getPlayers().stream().noneMatch(p -> Math.abs((p.getLocation().getBlockX() >> 4) - position[0]) <= world.getViewDistance()
                    && Math.abs((p.getLocation().getBlockZ() >> 4) - position[1]) <= world.getViewDistance())) chunk.unload(true);
        }, () -> done("prepare", "chunks=" + targets.size() + " complete=true"));
    }
    private List<Player> players() {
        return world.getPlayers().stream().sorted(Comparator.comparing(Player::getName)).toList();
    }
    private void noJobs() { if (!jobs.isEmpty()) throw new IllegalStateException("active jobs: " + jobs.keySet()); }
    private void place(String[] args) {
        requireWorld(); noJobs();
        int spacing = integer(args, 1, 96, 16, 1024);
        List<? extends Player> connected = Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName)).toList();
        if (connected.isEmpty()) throw new IllegalStateException("no connected players to place");
        phase("setup-placement"); start("place", "players=" + connected.size() + " spacing=" + spacing);
        int columns = (int)Math.ceil(Math.sqrt(connected.size()));
        List<Map<String, Object>> positions = new ArrayList<>();
        paced("place", connected.size(), 1, 3, index -> {
            Player player = connected.get(index);
            if (!player.isOnline()) throw new IllegalStateException("player disconnected during placement: " + player.getName());
            int x = (index % columns) * spacing, z = (index / columns) * spacing;
            int y = world.getHighestBlockYAt(x, z) + 1;
            // Ground pad permits honest small-radius packet movement without implementing a bot physics engine.
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                world.getBlockAt(x + dx, y - 1, z + dz).setType(Material.GRASS_BLOCK, false);
                world.getBlockAt(x + dx, y, z + dz).setType(Material.AIR, false);
                world.getBlockAt(x + dx, y + 1, z + dz).setType(Material.AIR, false);
            }
            player.setGameMode(GameMode.SURVIVAL); player.setAllowFlight(false); player.setInvulnerable(false);
            player.setFoodLevel(20); player.setHealth(player.getMaxHealth());
            if (!player.teleport(new Location(world, x + .5, y, z + .5))) throw new IllegalStateException("player teleport cancelled: " + player.getName());
            positions.add(Map.of("name", player.getName(), "uuid", player.getUniqueId().toString(), "x", x + .5, "y", y, "z", z + .5));
        }, () -> { measurement.file("player-placement.json", Map.of("world", world.getName(), "spacing", spacing, "players", positions)); done("place", "players=" + positions.size() + " complete=true"); });
    }
    private void spawn(String[] args) {
        requireWorld(); noJobs();
        int total = integer(args, 1, 100, 1, 100_000), radius = integer(args, 2, 24, 3, 128), perTick = integer(args, 3, 25, 1, 500);
        List<Player> players = players();
        if (players.isEmpty()) throw new IllegalStateException("AI workload requires real players in benchmark world");
        List<Location> centers = players.stream().map(Player::getLocation).toList();
        phase("setup-spawn"); start("spawn", "total=" + total + " centers=" + centers.size() + " radius=" + radius);
        paced("spawn", total, perTick, 3, index -> {
            Location center = centers.get(index % centers.size());
            double angle = random.nextDouble() * Math.PI * 2, distance = 3 + random.nextDouble() * (radius - 3);
            int x = (int)Math.floor(center.getX() + Math.cos(angle) * distance), z = (int)Math.floor(center.getZ() + Math.sin(angle) * distance);
            Location position = new Location(world, x + .5, world.getHighestBlockYAt(x, z) + 1, z + .5);
            Mob mob = index % 2 == 0 ? world.spawn(position, Cow.class, CreatureSpawnEvent.SpawnReason.CUSTOM)
                : world.spawn(position, Zombie.class, CreatureSpawnEvent.SpawnReason.CUSTOM);
            mob.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte)1);
            mob.addScoreboardTag("gpurbench_synthetic"); mob.setAI(true); mob.setAware(true);
            mob.setPersistent(true); mob.setRemoveWhenFarAway(false); mob.setInvulnerable(true);
            if (mob instanceof Zombie zombie) { zombie.setShouldBurnInDay(false); zombie.setTarget(players.get(index % players.size())); }
        }, () -> { census(Bukkit.getCurrentTick()); done("spawn", "added=" + total + " synthetic_alive=" + syntheticMobs + " complete=true"); });
    }
    private void remove() {
        requireWorld(); noJobs(); phase("setup-remove");
        List<Entity> marked = world.getEntities().stream().filter(this::synthetic).toList();
        start("remove", "count=" + marked.size());
        paced("remove", marked.size(), 100, 3, index -> marked.get(index).remove(), () -> done("remove", "removed=" + marked.size()));
    }
    private boolean synthetic(Entity entity) { return entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE); }

    private void removeNatural() {
        requireWorld(); noJobs(); phase("setup-remove-natural");
        // Only mobs in the marked isolated fixture dimension are removed.
        // Do not clear living players, other worlds, or the fixed fixture population.
        List<Entity> natural = world.getEntities().stream().filter(entity -> entity instanceof Mob && !synthetic(entity)).toList();
        start("removenatural", "count=" + natural.size());
        paced("removenatural", natural.size(), 100, 3, index -> natural.get(index).remove(),
            () -> { census(Bukkit.getCurrentTick()); done("removenatural", "removed=" + natural.size() + " synthetic_alive=" + syntheticMobs); });
    }

    /** Server teleports are explicitly synthetic chunk travel, not an emulation of client walking. */
    private void travel(String[] args) {
        requireWorld(); noJobs();
        int seconds = integer(args, 1, 30, 1, 3600);
        double speed = decimal(args, 2, 4.317, .1, 5.612);
        String direction = args.length > 3 ? args[3] : "east";
        double dx = 0, dz = 0;
        switch (direction) { case "east" -> dx = speed / 20; case "west" -> dx = -speed / 20; case "north" -> dz = -speed / 20; case "south" -> dz = speed / 20; default -> throw new IllegalArgumentException("direction east|west|north|south"); }
        List<Player> players = players(); if (players.isEmpty()) throw new IllegalStateException("no players in benchmark world");
        Map<UUID, Location> positions = new HashMap<>(); players.forEach(p -> positions.put(p.getUniqueId(), p.getLocation()));
        phase("synthetic-server-teleport-travel"); start("travel", "seconds_at_20tps=" + seconds + " speed=" + speed + " direction=" + direction);
        final double stepX = dx, stepZ = dz;
        paced("travel", seconds * 20, 1, 40, index -> {
            for (Player p : players) if (p.isOnline() && p.getWorld().equals(world)) {
                Location next = positions.get(p.getUniqueId()).add(stepX, 0, stepZ);
                next.setY(world.getHighestBlockYAt(next.getBlockX(), next.getBlockZ()) + 1);
                if (!p.teleport(next)) throw new IllegalStateException("travel teleport cancelled: " + p.getName());
            }
        }, () -> done("travel", "ticks=" + seconds * 20 + " synthetic=true"));
    }

    private void redstone(String[] args) {
        requireWorld(); noJobs();
        int total = integer(args, 1, 100, 1, 10_000);
        List<Location> centers = players().stream().map(Player::getLocation).toList();
        if (centers.isEmpty()) throw new IllegalStateException("redstone workload requires players for ticking chunks");
        phase("setup-redstone"); start("redstone", "circuits=" + total + " type=observer-feedback-and-lamps");
        paced("redstone", total, 5, 3, index -> {
            Location center = centers.get(index % centers.size()); int local = index / centers.size();
            int x = center.getBlockX() + 8 + (local % 8) * 6, z = center.getBlockZ() + 8 + (local / 8) * 4;
            int y = world.getHighestBlockYAt(x, z) + 2;
            for (int offset = -1; offset <= 2; offset++) {
                Block support = world.getBlockAt(x + offset, y - 1, z); support.setType(Material.STONE, false); circuitBlocks.add(support);
            }
            Block leftLamp = world.getBlockAt(x - 1, y, z), rightLamp = world.getBlockAt(x + 2, y, z);
            leftLamp.setType(Material.REDSTONE_LAMP, false); rightLamp.setType(Material.REDSTONE_LAMP, false);
            circuitBlocks.add(leftLamp); circuitBlocks.add(rightLamp);
            Block first = world.getBlockAt(x, y, z), second = world.getBlockAt(x + 1, y, z);
            Directional east = (Directional)Bukkit.createBlockData(Material.OBSERVER); east.setFacing(BlockFace.EAST);
            Directional west = (Directional)Bukkit.createBlockData(Material.OBSERVER); west.setFacing(BlockFace.WEST);
            first.setBlockData(east, true); second.setBlockData(west, true); circuitBlocks.add(first); circuitBlocks.add(second);
        }, () -> { measurement.file("redstone-placement.json", circuitBlocks.stream().map(b -> Map.of("world", b.getWorld().getName(), "x", b.getX(), "y", b.getY(), "z", b.getZ())).toList()); done("redstone", "circuits=" + total + " complete=true"); });
    }
    private void clearRedstone() {
        requireWorld(); noJobs(); phase("setup-clearredstone");
        List<Block> remove = List.copyOf(circuitBlocks); start("clearredstone", "blocks=" + remove.size());
        paced("clearredstone", remove.size(), 100, 3, index -> remove.get(index).setType(Material.AIR, true), () -> { circuitBlocks.clear(); done("clearredstone", "blocks=" + remove.size()); });
    }

    @FunctionalInterface private interface Work { void run(int index) throws Exception; }
    private void paced(String name, int total, int perTick, double budgetMs, Work work, Runnable complete) {
        if (total == 0) { complete.run(); return; }
        BukkitRunnable job = new BukkitRunnable() {
            int cursor;
            @Override public void run() {
                long begin = System.nanoTime(); int batch = 0;
                try {
                    while (cursor < total && batch < perTick && (batch == 0 || System.nanoTime() - begin < budgetMs * 1e6)) { work.run(cursor++); batch++; }
                    if (cursor >= total) { cancel(); jobs.remove(name); complete.run(); }
                } catch (Exception failure) {
                    cancel(); jobs.remove(name);
                    getLogger().severe("GPURBENCH_ERROR command=" + name + " progress=" + cursor + "/" + total + " reason=" + failure);
                    measurement.event(Map.of("type", "job_error", "command", name, "progress", cursor, "total", total, "error", failure.toString()));
                }
            }
        };
        jobs.put(name, job.runTaskTimer(this, 1, 1));
    }

    @EventHandler(priority = EventPriority.LOWEST) public void tickStart(ServerTickStartEvent event) {
        if (finished) return;
        long now = System.nanoTime(); intervalMs = previousStartNs == 0 ? -1 : (now - previousStartNs) / 1e6;
        previousStartNs = now; tickStartNs = now; tickPhaseId = measurement.phaseId(); tickPhase = measurement.phaseName();
    }
    @EventHandler(priority = EventPriority.MONITOR) public void tickEnd(ServerTickEndEvent event) {
        if (finished) return;
        long end = System.nanoTime();
        if ((long)event.getTickNumber() - surveyTick >= 20) census(event.getTickNumber());
        measurement.sample(new Measurements.Sample(event.getTickNumber(), System.currentTimeMillis(), tickStartNs, end,
            tickPhaseId, tickPhase, event.getTickDuration(), intervalMs, Bukkit.getOnlinePlayers().size(), world == null ? 0 : world.getPlayers().size(),
            living, mobs, tickedMobs, syntheticMobs, chunks, event.getTickNumber() - surveyTick, surveyMs,
            redstoneEvents, spawnEvents, moveEvents, chunkLoadEvents, damageEvents, gpuStatus, gpuCompleted, cpuFallbacks, measurement.gc()));
    }
    private void census(long tick) {
        long start = System.nanoTime(); living = mobs = tickedMobs = syntheticMobs = chunks = 0;
        for (World loaded : Bukkit.getWorlds()) {
            chunks += loaded.getLoadedChunks().length;
            for (LivingEntity entity : loaded.getLivingEntities()) {
                living++;
                if (entity instanceof Mob) { mobs++; if (entity.isTicking()) tickedMobs++; if (synthetic(entity)) syntheticMobs++; }
            }
        }
        surveyTick = tick; updateGpu(); surveyMs = (System.nanoTime() - start) / 1e6;
    }
    private void updateGpu() {
        if (gpuUnavailable) return;
        try {
            if (gpuServiceMethod == null) gpuServiceMethod = Class.forName("org.gpur.GPurServices").getMethod("compute");
            Object compute = gpuServiceMethod.invoke(null);
            if (compute == null) { gpuStatus = "service_not_initialized"; return; }
            if (gpuStatusMethod == null || !gpuStatusMethod.getDeclaringClass().isInstance(compute)) gpuStatusMethod = compute.getClass().getMethod("status");
            gpuStatus = String.valueOf(gpuStatusMethod.invoke(compute));
            Matcher done = GPU_RESULTS.matcher(gpuStatus), fallback = CPU_FALLBACKS.matcher(gpuStatus);
            gpuCompleted = done.find() ? Long.parseLong(done.group(1)) : -1; cpuFallbacks = fallback.find() ? Long.parseLong(fallback.group(1)) : -1;
        } catch (ClassNotFoundException ordinaryPaper) { gpuUnavailable = true; gpuStatus = "ordinary_paper_cpu_no_gpur_services"; }
        catch (ReflectiveOperationException | LinkageError unavailable) { gpuStatus = "reflection_error:" + unavailable.getClass().getSimpleName(); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void redstoneEvent(BlockRedstoneEvent event) { if (world != null && event.getBlock().getWorld().equals(world)) redstoneEvents++; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void spawnEvent(CreatureSpawnEvent event) { if (world != null && event.getLocation().getWorld().equals(world)) spawnEvents++; }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void moveEvent(PlayerMoveEvent event) {
        if (world != null && event.getPlayer().getWorld().equals(world)) {
            moveEvents++;
            movementTelemetry.observe(event, world);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void chunkEvent(ChunkLoadEvent event) { if (world != null && event.getWorld().equals(world)) chunkLoadEvents++; }
    // Keep synthetic client counts stable while still executing Mob AI, pathfinding,
    // attacks, and ordinary CPU damage callbacks. This protection is part of the fixture.
    @EventHandler(priority = EventPriority.NORMAL) public void protectBenchPlayers(EntityDamageEvent event) {
        if (world != null && event.getEntity() instanceof Player && event.getEntity().getWorld().equals(world)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void damageEvent(EntityDamageEvent event) { if (world != null && event.getEntity().getWorld().equals(world)) damageEvents++; }
    // Conversion replaces an entity without preserving fixture ownership or protection.
    // Keep only the prescribed fixture population fixed; natural mobs behave normally.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true) public void protectFixturePopulation(EntityTransformEvent event) {
        if (world != null && event.getEntity().getWorld().equals(world) && synthetic(event.getEntity())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void joinEvent(PlayerJoinEvent event) {
        measurement.event(Map.of("type", "player_join", "name", event.getPlayer().getName(), "online", Bukkit.getOnlinePlayers().size(), "epoch_ms", System.currentTimeMillis()));
    }
    private void finish() {
        if (finished) { getLogger().info("GPURBENCH_DONE command=finish already_finished=true"); return; }
        noJobs(); saveMovementTelemetry(); finished = true;
        measurement.finish(metadata());
        // Completion marker means CSV, events and final JSON have actually been closed, not only queued.
        new Thread(() -> {
            try {
                if (!measurement.awaitFinish(30_000)) getLogger().severe("GPURBENCH_INVALID finish writer timed out");
                else if (!measurement.error().equals("none")) getLogger().severe("GPURBENCH_INVALID finish writer error=" + measurement.error());
                else getLogger().info("GPURBENCH_DONE command=finish output=" + measurement.directory().toAbsolutePath());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "GPurBench-finish-waiter").start();
    }
    @Override public void onDisable() {
        for (BukkitTask task : jobs.values()) task.cancel(); jobs.clear();
        if (measurement != null) { saveMovementTelemetry(); measurement.close(); }
    }

    /** Aggregates real, uncancelled server-side PlayerMoveEvent from/to movement by phase and UUID. */
    private static final class MovementTelemetry {
        private static final double NONZERO_EPSILON_SQUARED = 1.0e-12;
        private final List<Map<String, Object>> phases = new ArrayList<>();
        private Phase current;
        private int walkingPercent = -1;
        private boolean saved;

        private static final class PlayerMovement {
            final UUID uuid;
            String name;
            long samples, nonzeroSamples, firstEpochMs, lastEpochMs;
            double accumulatedDistance, accumulatedHorizontalDistance, maxStep;
            Map<String, Object> firstNonzeroFrom, lastNonzeroTo;

            PlayerMovement(Player player) { uuid = player.getUniqueId(); name = player.getName(); }

            Map<String, Object> json() {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("uuid", uuid.toString()); value.put("name", name);
                value.put("samples", samples); value.put("nonzero_displacement_samples", nonzeroSamples);
                value.put("accumulated_from_to_distance_blocks", accumulatedDistance);
                value.put("accumulated_horizontal_distance_blocks", accumulatedHorizontalDistance);
                value.put("max_step_blocks", maxStep);
                value.put("first_epoch_ms", samples == 0 ? null : firstEpochMs);
                value.put("last_epoch_ms", samples == 0 ? null : lastEpochMs);
                value.put("first_nonzero_from", firstNonzeroFrom);
                value.put("last_nonzero_to", lastNonzeroTo);
                return value;
            }
        }

        private static final class Phase {
            final long id, startedEpochMs;
            final String name, world;
            final int walkingPercent, playersAtStart;
            final Map<UUID, PlayerMovement> players = new LinkedHashMap<>();
            Phase(long id, String name, String world, int walkingPercent, int playersAtStart) {
                this.id = id; this.name = name; this.world = world; this.walkingPercent = walkingPercent;
                this.playersAtStart = playersAtStart; this.startedEpochMs = System.currentTimeMillis();
            }
        }

        void begin(long phaseId, String name, World world) {
            int playerCount = world == null ? 0 : world.getPlayers().size();
            current = new Phase(phaseId, name, world == null ? "none" : world.getName(), walkingPercent, playerCount);
            if (world != null) for (Player player : world.getPlayers()) current.players.put(player.getUniqueId(), new PlayerMovement(player));
        }

        void observe(PlayerMoveEvent event, World benchmarkWorld) {
            if (current == null || benchmarkWorld == null) return;
            Location from = event.getFrom(), to = event.getTo();
            if (to == null || from.getWorld() == null || to.getWorld() == null
                    || !benchmarkWorld.equals(from.getWorld()) || !benchmarkWorld.equals(to.getWorld())) return;
            Player player = event.getPlayer();
            PlayerMovement movement = current.players.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerMovement(player));
            movement.name = player.getName();
            long now = System.currentTimeMillis();
            if (movement.samples == 0) movement.firstEpochMs = now;
            movement.lastEpochMs = now;
            movement.samples++;
            double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= NONZERO_EPSILON_SQUARED) return;
            double distance = Math.sqrt(distanceSquared);
            movement.nonzeroSamples++;
            movement.accumulatedDistance += distance;
            movement.accumulatedHorizontalDistance += Math.hypot(dx, dz);
            movement.maxStep = Math.max(movement.maxStep, distance);
            if (movement.firstNonzeroFrom == null) movement.firstNonzeroFrom = location(from);
            movement.lastNonzeroTo = location(to);
        }

        private static Map<String, Object> location(Location location) {
            return Map.of("x", location.getX(), "y", location.getY(), "z", location.getZ());
        }

        Map<String, Object> end(World world) {
            if (current == null) return null;
            Map<String, Object> summary = new LinkedHashMap<>();
            List<PlayerMovement> ordered = new ArrayList<>(current.players.values());
            ordered.sort(Comparator.comparing(player -> player.uuid.toString()));
            List<Map<String, Object>> playerRows = ordered.stream().map(PlayerMovement::json).toList();
            long samples = ordered.stream().mapToLong(player -> player.samples).sum();
            long nonzeroSamples = ordered.stream().mapToLong(player -> player.nonzeroSamples).sum();
            long movers = ordered.stream().filter(player -> player.nonzeroSamples > 0).count();
            double distance = ordered.stream().mapToDouble(player -> player.accumulatedDistance).sum();
            int expected = current.walkingPercent < 0 ? -1 : current.playersAtStart * current.walkingPercent / 100;
            summary.put("phase_id", current.id); summary.put("phase", current.name);
            summary.put("world", current.world); summary.put("started_epoch_ms", current.startedEpochMs);
            summary.put("ended_epoch_ms", System.currentTimeMillis());
            summary.put("configured_walking_percent", current.walkingPercent < 0 ? null : current.walkingPercent);
            summary.put("configured_walking_fraction", current.walkingPercent < 0 ? null : current.walkingPercent / 100.0);
            summary.put("players_at_start", current.playersAtStart);
            summary.put("players_at_end", world == null ? 0 : world.getPlayers().size());
            summary.put("expected_walking_players", expected < 0 ? null : expected);
            summary.put("sample_count", samples); summary.put("nonzero_displacement_samples", nonzeroSamples);
            summary.put("distinct_mover_count", movers);
            summary.put("accumulated_nonzero_displacement_blocks", distance);
            summary.put("players", playerRows);
            phases.add(summary);
            current = null;
            return summary;
        }

        Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", 1); result.put("configured_walking_percent", walkingPercent < 0 ? null : walkingPercent);
            result.put("configured_walking_fraction", walkingPercent < 0 ? null : walkingPercent / 100.0);
            result.put("phases", new ArrayList<>(phases));
            return result;
        }
    }
}
