package org.gpur.bench;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/** Immutable main-thread snapshots only; all files and percentile calculations are off-thread. */
final class Measurements implements AutoCloseable {
    record Gc(long count, long collectionMs, long notifications, long notificationMs, long maxPauseMs) {}
    record Sample(int tick, long epochMs, long startNs, long endNs, long phaseId,
                  String phase, double mspt, double intervalMs, int players, int benchPlayers,
                  int living, int mobs, int tickedMobs, int syntheticMobs, int chunks,
                  long surveyAgeTicks, double surveyMs, long redstoneEvents, long spawnEvents,
                  long moveEvents, long chunkLoadEvents, long damageEvents,
                  String gpuStatus, long gpuCompleted, long cpuFallbacks, Gc gc) {}
    record Boundary(long id, String name, long ns, long epochMs, Gc gc, Map<String, Object> metadata) {}
    private record Stop(long ns, Gc gc, Map<String, Object> metadata) {}
    private record FileWrite(String name, Object value) {}
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicLong notificationCount = new AtomicLong();
    private final AtomicLong notificationMs = new AtomicLong();
    private final AtomicLong maxPauseMs = new AtomicLong();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener gcListener;
    private final Thread writer;
    private final Path directory;
    private final Logger logger;
    private volatile String phaseName = "startup";
    private volatile IOException failure;
    private volatile boolean closed;
    private long phaseId;
    private final long originNs = System.nanoTime();

    Measurements(Path directory, Logger logger, Map<String, Object> metadata) throws IOException {
        this.directory = directory;
        this.logger = logger;
        Files.createDirectories(directory);
        gcListener = (notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType()) || closed) return;
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            long duration = info.getGcInfo().getDuration();
            notificationCount.incrementAndGet();
            notificationMs.addAndGet(duration);
            maxPauseMs.accumulateAndGet(duration, Math::max);
            queue.add(Map.of("type", "gc", "epoch_ms", notification.getTimeStamp(), "phase", phaseName,
                "name", info.getGcName(), "action", info.getGcAction(), "cause", info.getGcCause(),
                "start_uptime_ms", info.getGcInfo().getStartTime(), "duration_ms", duration));
        };
        for (GarbageCollectorMXBean collector : collectors) {
            if (collector instanceof NotificationEmitter emitter) {
                try { emitter.addNotificationListener(gcListener, null, null); emitters.add(emitter); }
                catch (RuntimeException unsupported) { logger.warning("GC notification unavailable for " + collector.getName()); }
            }
        }
        queue.add(new Boundary(0, "startup", originNs, System.currentTimeMillis(), gc(), metadata));
        writer = new Thread(this::writeLoop, "GPurBench-file-writer");
        writer.setDaemon(true);
        writer.start();
    }

    Path directory() { return directory; }
    String phaseName() { return phaseName; }
    long phaseId() { return phaseId; }
    int backlog() { return queue.size(); }
    String error() { return failure == null ? "none" : failure.toString(); }
    Gc gc() {
        long count = 0, ms = 0;
        for (GarbageCollectorMXBean bean : collectors) {
            count += Math.max(0, bean.getCollectionCount());
            ms += Math.max(0, bean.getCollectionTime());
        }
        return new Gc(count, ms, notificationCount.get(), notificationMs.get(), maxPauseMs.get());
    }
    void phase(String name, Map<String, Object> metadata) {
        if (closed) throw new IllegalStateException("measurement finished; restart server for a new run");
        phaseName = name;
        queue.add(new Boundary(++phaseId, name, System.nanoTime(), System.currentTimeMillis(), gc(), metadata));
    }
    void sample(Sample sample) { if (!closed) queue.add(sample); }
    void event(Map<String, Object> event) { if (!closed) queue.add(event); }
    void file(String name, Object value) { if (!closed) queue.add(new FileWrite(name, value)); }

    private static final class Stats {
        final long id, beginNs, epochMs;
        final String name;
        final Gc beginGc;
        final Map<String, Object> metadata;
        final List<Double> durations = new ArrayList<>();
        long endNs, firstStartNs = -1, lastStartNs = -1, intervals;
        double intervalTotalMs;
        long above50, above100;
        int minPlayers = Integer.MAX_VALUE, maxPlayers, minBenchPlayers = Integer.MAX_VALUE, maxBenchPlayers;
        Gc endGc;
        Map<String, Object> endMetadata = Map.of();
        boolean complete;
        Stats(Boundary b) {
            id = b.id; name = b.name; beginNs = b.ns; endNs = b.ns;
            epochMs = b.epochMs; beginGc = b.gc; endGc = b.gc; metadata = b.metadata;
        }
        void add(Sample s) {
            durations.add(s.mspt); endNs = Math.max(endNs, s.endNs); endGc = s.gc;
            if (firstStartNs < 0) firstStartNs = s.startNs;
            if (lastStartNs >= 0 && s.startNs > lastStartNs) { intervalTotalMs += (s.startNs - lastStartNs) / 1e6; intervals++; }
            lastStartNs = s.startNs;
            if (s.mspt > 50) above50++;
            if (s.mspt > 100) above100++;
            minPlayers = Math.min(minPlayers, s.players); maxPlayers = Math.max(maxPlayers, s.players);
            minBenchPlayers = Math.min(minBenchPlayers, s.benchPlayers); maxBenchPlayers = Math.max(maxBenchPlayers, s.benchPlayers);
        }
        Map<String, Object> json() {
            List<Double> sorted = new ArrayList<>(durations); sorted.sort(Comparator.naturalOrder());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id); result.put("phase", name); result.put("complete", complete);
            result.put("start_epoch_ms", epochMs); result.put("wall_seconds", (endNs - beginNs) / 1e9);
            result.put("ticks", sorted.size()); result.put("actual_wall_tps", endNs > beginNs ? sorted.size() * 1e9 / (endNs - beginNs) : null);
            result.put("start_interval_tps", intervalTotalMs > 0 ? intervals * 1000.0 / intervalTotalMs : null);
            result.put("p50_ms", percentile(sorted, .50)); result.put("p95_ms", percentile(sorted, .95));
            result.put("p99_ms", percentile(sorted, .99)); result.put("max_ms", sorted.isEmpty() ? null : sorted.getLast());
            result.put("mean_ms", sorted.isEmpty() ? null : sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            result.put("ticks_over_50_ms", above50); result.put("ticks_over_100_ms", above100);
            result.put("players_min", minPlayers == Integer.MAX_VALUE ? 0 : minPlayers); result.put("players_max", maxPlayers);
            result.put("bench_players_min", minBenchPlayers == Integer.MAX_VALUE ? 0 : minBenchPlayers); result.put("bench_players_max", maxBenchPlayers);
            result.put("gc_collections", endGc.count - beginGc.count); result.put("gc_collection_ms", endGc.collectionMs - beginGc.collectionMs);
            result.put("gc_notification_count", endGc.notifications - beginGc.notifications);
            result.put("gc_notification_duration_ms", endGc.notificationMs - beginGc.notificationMs);
            result.put("gc_run_max_notification_ms", endGc.maxPauseMs);
            result.put("metadata", metadata); result.put("end_metadata", endMetadata);
            return result;
        }
    }

    static Double percentile(List<Double> sorted, double p) {
        return sorted.isEmpty() ? null : sorted.get(Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
    }
    private void writeLoop() {
        Map<Long, Stats> phases = new LinkedHashMap<>();
        Stats all = null;
        Stats current = null;
        ArrayDeque<Sample> rolling = new ArrayDeque<>();
        long lastSummaryNs = 0;
        try (BufferedWriter csv = Files.newBufferedWriter(directory.resolve("ticks.csv"), StandardCharsets.UTF_8);
             BufferedWriter events = Files.newBufferedWriter(directory.resolve("events.jsonl"), StandardCharsets.UTF_8)) {
            csv.write("tick,epoch_ms,elapsed_ms,phase_id,phase,mspt,start_interval_ms,online_players,bench_players,living_entities,mobs,in_ticking_chunk_mobs,synthetic_mobs,loaded_chunks,survey_age_ticks,survey_ms,redstone_events,spawn_events,move_events,chunk_load_events,damage_events,gpu_completed,cpu_fallbacks,gpu_status,gc_count,gc_collection_ms,gc_notification_count,gc_notification_ms,gc_max_notification_ms\n");
            while (true) {
                Object item = queue.take();
                if (item instanceof Boundary b) {
                    if (current != null) { current.endNs = b.ns; current.endGc = b.gc; current.endMetadata = b.metadata; current.complete = true; }
                    current = new Stats(b); phases.put(b.id, current);
                    if (all == null) all = new Stats(new Boundary(-1, "all_including_setup_and_spikes", originNs, b.epochMs, b.gc, b.metadata));
                    events.write(json(Map.of("type", "phase", "id", b.id, "phase", b.name, "epoch_ms", b.epochMs, "metadata", b.metadata)) + "\n");
                } else if (item instanceof Sample s) {
                    Stats phase = phases.get(s.phaseId);
                    if (phase != null) {
                        long boundaryEnd = phase.endNs; Gc boundaryGc = phase.endGc;
                        phase.add(s);
                        // A command can change phase inside the tick; preserve the real boundary wall time.
                        if (phase.complete) { phase.endNs = boundaryEnd; phase.endGc = boundaryGc; }
                    }
                    all.add(s); rolling.addLast(s);
                    while (rolling.size() > 600) rolling.removeFirst();
                    csv.write(s.tick + "," + s.epochMs + "," + ((s.startNs - originNs) / 1e6) + "," + s.phaseId + "," + csv(s.phase)
                        + "," + s.mspt + "," + (s.intervalMs < 0 ? "" : s.intervalMs) + "," + s.players + "," + s.benchPlayers
                        + "," + s.living + "," + s.mobs + "," + s.tickedMobs + "," + s.syntheticMobs + "," + s.chunks
                        + "," + s.surveyAgeTicks + "," + s.surveyMs + "," + s.redstoneEvents + "," + s.spawnEvents
                        + "," + s.moveEvents + "," + s.chunkLoadEvents + "," + s.damageEvents
                        + "," + s.gpuCompleted + "," + s.cpuFallbacks + "," + csv(s.gpuStatus)
                        + "," + s.gc.count + "," + s.gc.collectionMs + "," + s.gc.notifications + "," + s.gc.notificationMs + "," + s.gc.maxPauseMs + "\n");
                    if (s.endNs - lastSummaryNs >= TimeUnit.SECONDS.toNanos(5)) {
                        writeSummary(all, phases.values(), rolling, false); csv.flush(); events.flush(); lastSummaryNs = s.endNs;
                    }
                } else if (item instanceof FileWrite f) {
                    writeJson(directory.resolve(f.name), f.value);
                } else if (item instanceof Stop stop) {
                    if (current != null) { current.endNs = stop.ns; current.endGc = stop.gc; current.endMetadata = stop.metadata; current.complete = true; }
                    if (all != null) { all.endNs = stop.ns; all.endGc = stop.gc; all.endMetadata = stop.metadata; all.complete = true; }
                    writeSummary(all, phases.values(), rolling, true); break;
                } else events.write(json(item) + "\n");
            }
        } catch (IOException io) { failure = io; logger.severe("GPURBENCH_INVALID writer failure: " + io); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); logger.severe("GPURBENCH_INVALID writer interrupted"); }
    }
    private void writeSummary(Stats all, Collection<Stats> phases, ArrayDeque<Sample> rolling, boolean finished) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 1); result.put("finished", finished); result.put("writer_queue_depth", queue.size());
        result.put("dropped_samples", 0); result.put("all", all == null ? Map.of() : all.json());
        result.put("phases", phases.stream().map(Stats::json).toList());
        if (!rolling.isEmpty()) {
            Sample first = rolling.getFirst();
            Stats window = new Stats(new Boundary(-2, "rolling_last_600_ticks", first.startNs, first.epochMs, first.gc, Map.of()));
            rolling.forEach(window::add); result.put("rolling", window.json());
        }
        result.put("gc_notification_collectors", emitters.size());
        result.put("gc_duration_note", "MXBean collection time and notification durations are collector-reported elapsed durations; concurrent GC durations are not proven stop-the-world pause times.");
        result.put("tick_note", "Paper end-event duration in ms. No samples or spikes excluded. Start intervals and wall TPS are uncapped. Entity census every 20 ticks, isTicking means inside a ticking chunk, not proof of per-entity full AI execution.");
        writeJson(directory.resolve("summary.json"), result);
    }
    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\""; }
    static void writeJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json(value) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (var entry : map.entrySet()) { if (out.length() > 1) out.append(','); out.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue())); }
            return out.append('}').toString();
        }
        if (value instanceof Collection<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) { if (out.length() > 1) out.append(','); out.append(json(item)); }
            return out.append(']').toString();
        }
        String s = value.toString(); StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) { case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); } }
        }
        return out.append('"').toString();
    }
    void finish(Map<String, Object> metadata) {
        if (closed) return;
        closed = true;
        for (NotificationEmitter emitter : emitters) {
            try { emitter.removeNotificationListener(gcListener); } catch (Exception ignored) {}
        }
        queue.add(new Stop(System.nanoTime(), gc(), metadata));
    }
    boolean awaitFinish(long timeoutMs) throws InterruptedException { writer.join(timeoutMs); return !writer.isAlive(); }
    @Override public void close() {
        finish(Map.of("reason", "plugin_disable", "time", Instant.now().toString()));
        try { if (!awaitFinish(15_000)) logger.severe("GPURBENCH_INVALID writer did not finish within 15s"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
