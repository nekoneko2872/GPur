package org.gpur.worldgen;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Collects tiny immutable tick samples on the server thread and aggregates/writes them off-thread. */
final class TickMetrics implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final String simulationMode;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile String writerFailure;
    private long previousStartNs;
    private long previousPhaseId;
    private long currentPhaseId;
    private long currentStartNs;
    private long currentIntervalNs = -1;
    private long currentTick;
    private long currentEpochMs;
    private boolean tickOpen;
    private long endAfterTickPhaseId;
    private boolean endAfterTickSuccess;
    private String endAfterTickError;
    private boolean closed;

    TickMetrics(String simulationMode) {
        this.simulationMode = simulationMode;
        this.writer = new Thread(this::writeLoop, "GPurWorldgenProbe-tick-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    void begin(long phaseId, String label, String phase, Path directory) {
        this.queue.add(new PhaseStart(phaseId, label, phase,
            directory.resolve(phase + "-tick-samples.csv"),
            directory.resolve(phase + "-tick-metrics.json"), this.simulationMode));
    }

    void tickStart(ServerTickStartEvent event, long phaseId) {
        long now = System.nanoTime();
        this.currentIntervalNs = phaseId != 0 && this.previousPhaseId == phaseId && this.previousStartNs != 0
            ? now - this.previousStartNs : -1;
        this.previousStartNs = now;
        this.previousPhaseId = phaseId;
        this.currentPhaseId = phaseId;
        this.currentStartNs = now;
        this.currentTick = event.getTickNumber();
        this.currentEpochMs = System.currentTimeMillis();
        this.tickOpen = true;
    }

    void tickEnd(ServerTickEndEvent event) {
        long end = System.nanoTime();
        long phaseId = this.currentPhaseId;
        if (this.tickOpen && phaseId != 0) {
            this.queue.add(new TickSample(phaseId, this.currentTick, this.currentEpochMs,
                this.currentStartNs, end - this.currentStartNs, this.currentIntervalNs));
        }
        this.tickOpen = false;
        if (this.endAfterTickPhaseId != 0 && this.endAfterTickPhaseId == phaseId) {
            this.queue.add(new PhaseEnd(this.endAfterTickPhaseId, this.endAfterTickSuccess, this.endAfterTickError));
            this.endAfterTickPhaseId = 0;
            this.endAfterTickError = null;
        }
    }

    void end(long phaseId, boolean success, String error) {
        if (this.tickOpen && this.currentPhaseId == phaseId) {
            // The sample for this tick is queued by ServerTickEndEvent first.
            this.endAfterTickPhaseId = phaseId;
            this.endAfterTickSuccess = success;
            this.endAfterTickError = error;
        } else {
            this.queue.add(new PhaseEnd(phaseId, success, error));
        }
    }

    String failure() { return this.writerFailure; }

    private void writeLoop() {
        Map<Long, PhaseWriter> phases = new LinkedHashMap<>();
        try {
            while (true) {
                Object item = this.queue.take();
                if (item instanceof PhaseStart start) {
                    Files.createDirectories(start.csv().getParent());
                    BufferedWriter csv = Files.newBufferedWriter(start.csv(), StandardCharsets.UTF_8);
                    csv.write("tick,epoch_ms,start_monotonic_ns,tick_duration_ms,start_interval_ms\n");
                    phases.put(start.id(), new PhaseWriter(start, csv));
                } else if (item instanceof TickSample sample) {
                    PhaseWriter phase = phases.get(sample.phaseId());
                    if (phase != null) phase.add(sample);
                } else if (item instanceof PhaseEnd end) {
                    PhaseWriter phase = phases.remove(end.id());
                    if (phase != null) phase.finish(end.success(), end.error());
                } else if (item == Stop.INSTANCE) {
                    for (PhaseWriter phase : phases.values()) phase.finish(false, "plugin disabled before phase completion");
                    phases.clear();
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.writerFailure = "tick writer interrupted";
        } catch (Throwable failure) {
            this.writerFailure = failure.toString();
            try {
                Path errorPath = Path.of("plugins", "GPurWorldgenProbe", "tick-metrics-error.txt");
                Files.createDirectories(errorPath.getParent());
                Files.writeString(errorPath, this.writerFailure + "\n", StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.endAfterTickPhaseId != 0) {
            this.queue.add(new PhaseEnd(this.endAfterTickPhaseId, this.endAfterTickSuccess, this.endAfterTickError));
            this.endAfterTickPhaseId = 0;
            this.endAfterTickError = null;
        }
        this.queue.add(Stop.INSTANCE);
        try {
            this.writer.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record PhaseStart(long id, String label, String phase, Path csv, Path report,
                              String simulationMode) {}
    private record TickSample(long phaseId, long tick, long epochMs, long startNs,
                              long durationNs, long intervalNs) {}
    private record PhaseEnd(long id, boolean success, String error) {}
    private enum Stop { INSTANCE }

    private static final class PhaseWriter {
        private final PhaseStart start;
        private final BufferedWriter csv;
        private final List<Double> durationsMs = new ArrayList<>();
        private final List<Double> intervalsMs = new ArrayList<>();
        private long firstStartNs = -1;
        private long lastStartNs = -1;
        private long over50Ms;
        private long over100Ms;

        PhaseWriter(PhaseStart start, BufferedWriter csv) {
            this.start = start;
            this.csv = csv;
        }

        void add(TickSample sample) throws IOException {
            double durationMs = sample.durationNs() / 1_000_000.0;
            double intervalMs = sample.intervalNs() < 0 ? Double.NaN : sample.intervalNs() / 1_000_000.0;
            this.csv.write(Long.toString(sample.tick()));
            this.csv.write(','); this.csv.write(Long.toString(sample.epochMs()));
            this.csv.write(','); this.csv.write(Long.toString(sample.startNs()));
            this.csv.write(','); this.csv.write(Double.toString(durationMs));
            this.csv.write(',');
            if (Double.isFinite(intervalMs)) this.csv.write(Double.toString(intervalMs));
            this.csv.write('\n');
            this.durationsMs.add(durationMs);
            if (Double.isFinite(intervalMs)) this.intervalsMs.add(intervalMs);
            if (durationMs > 50) this.over50Ms++;
            if (durationMs > 100) this.over100Ms++;
            if (this.firstStartNs < 0) this.firstStartNs = sample.startNs();
            this.lastStartNs = sample.startNs();
        }

        void finish(boolean success, String error) throws IOException {
            this.csv.flush();
            this.csv.close();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", 1);
            report.put("label", this.start.label());
            report.put("phase", this.start.phase());
            report.put("simulation_mode", this.start.simulationMode());
            report.put("complete", success);
            report.put("error", error);
            report.put("raw_csv", this.start.csv().toAbsolutePath().toString());
            report.put("tick_samples", this.durationsMs.size());
            report.put("start_interval_samples", this.intervalsMs.size());
            double wallSeconds = this.firstStartNs >= 0 && this.lastStartNs > this.firstStartNs
                ? (this.lastStartNs - this.firstStartNs) / 1_000_000_000.0 : 0;
            report.put("wall_seconds", wallSeconds);
            report.put("measured_tps", wallSeconds > 0 ? this.intervalsMs.size() / wallSeconds : null);
            report.put("tick_duration_ms", statistics(this.durationsMs));
            report.put("tick_start_interval_ms", statistics(this.intervalsMs));
            report.put("ticks_over_50ms", this.over50Ms);
            report.put("ticks_over_100ms", this.over100Ms);
            report.put("finished_utc", Instant.now().toString());
            Path temporary = this.start.report().resolveSibling(this.start.report().getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(report) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, this.start.report(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, this.start.report(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static Map<String, Object> statistics(List<Double> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", values.size());
            if (values.isEmpty()) {
                result.put("p50", null); result.put("p95", null); result.put("p99", null); result.put("max", null);
                return result;
            }
            List<Double> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            result.put("p50", percentile(sorted, .50));
            result.put("p95", percentile(sorted, .95));
            result.put("p99", percentile(sorted, .99));
            result.put("max", sorted.getLast());
            return result;
        }

        private static double percentile(List<Double> sorted, double percentile) {
            int index = Math.max(0, (int)Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }
}
