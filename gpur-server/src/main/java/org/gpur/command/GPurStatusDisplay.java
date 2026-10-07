package org.gpur.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.gpur.compute.ComputeService.DeviceWorkloadStatus;
import org.gpur.compute.ComputeService.StatusSnapshot;

/** Human-readable command output, separate from the compute service's machine-readable diagnostics. */
final class GPurStatusDisplay {
    record Options(boolean gpuEnabled, boolean force, boolean distancesEnabled, boolean antiXrayEnabled,
                   boolean preloadingEnabled, int extraLoadRadius, boolean flightRateBoost) {}

    private GPurStatusDisplay() {}

    static List<Component> render(StatusSnapshot snapshot, Options options, boolean detail) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("GPur 26.2 | Compute status", NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
        List<DeviceWorkloadStatus> devices = snapshot == null ? List.of() : snapshot.workloads().stream()
            .filter(workload -> workload.workload() == 1).toList();
        long available = devices.stream().filter(DeviceWorkloadStatus::available).count();
        if (snapshot == null || snapshot.stopped()) {
            lines.add(row("Mode: ", "CPU | compute service stopped", NamedTextColor.YELLOW));
        } else if (!options.gpuEnabled()) {
            lines.add(row("Mode: ", "CPU | GPU disabled in config", NamedTextColor.GRAY));
        } else if (available == 0) {
            lines.add(row("Mode: ", "CPU | no GPU available", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("Check startup logs for device selection, Vulkan support, and validation errors.")));
        } else {
            lines.add(row("GPUs available: ", available + " / " + devices.size(), NamedTextColor.GREEN)
                .hoverEvent(Component.text("Available means the device passed startup validation. It does not mean every calculation is running on the GPU.")));
        }
        if (options.gpuEnabled() && options.force()) {
            lines.add(row("GPU force: ", "ON (diagnostic mode)", NamedTextColor.YELLOW)
                .hoverEvent(Component.text("Performance cooldown is bypassed. Capacity limits and result validation still apply.")));
        }
        if (snapshot != null) {
            lines.add(row("GPU results: ", count(snapshot.gpuResults()) + " | CPU fallbacks: " + count(snapshot.cpuFallbacks()), NamedTextColor.WHITE)
                .hoverEvent(Component.text("Totals since start/reload. Results are accepted GPU batches, not players, packets, or ticks. CPU fallbacks count submitted compute attempts only; earlier CPU paths are excluded.")));
        }
        int index = 0;
        for (DeviceWorkloadStatus device : devices) {
            lines.add(Component.empty());
            String state = !device.available() ? "Unavailable" : device.busy() == 0 ? "Available (idle)" : "Running (" + device.busy() + " in flight)";
            lines.add(Component.text("GPU " + ++index + ": " + shortName(device.name()), NamedTextColor.AQUA)
                .append(Component.text(" | " + state, device.available() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .hoverEvent(Component.text(device.name() + "\nUUID: " + device.uuid()
                    + "\nIn flight: occupied execution contexts, not GPU utilization.")));
            for (DeviceWorkloadStatus workload : snapshot.workloads()) {
                if (!workload.uuid().equals(device.uuid())) continue;
                lines.add(workloadRow(snapshot, options, workload));
                if (detail) addWorkloadDetails(lines, workload);
            }
            if (detail) lines.add(row("  UUID: ", device.uuid(), NamedTextColor.GRAY));
        }
        lines.add(Component.empty());
        lines.add(row("CPU: ", "Mob AI, redstone, plugins", NamedTextColor.GRAY));
        lines.add(row("Terrain: ", "CPU (original generator)", NamedTextColor.GRAY));
        lines.add(row("Preload: ", options.preloadingEnabled()
            ? "CPU priority | extra radius: " + options.extraLoadRadius() : "Disabled", NamedTextColor.GRAY)
            .hoverEvent(Component.text("Preloading changes chunk queue priority on CPU. Extra radius expands loading/generation in all directions. It is not GPU terrain generation.")));
        if (options.flightRateBoost()) {
            lines.add(row("Flight rate boost: ", "ON (higher CPU demand)", NamedTextColor.YELLOW));
        }
        if (detail && snapshot != null) {
            lines.add(row("GPU admission skips (since reload): ", "", NamedTextColor.GRAY));
            lines.add(row("  Distances: ", count(snapshot.distanceSkips()) + " | Anti-Xray: " + count(snapshot.antiXraySkips()), NamedTextColor.WHITE));
            lines.add(Component.text("Skips are failed admission checks, not total CPU work.", NamedTextColor.GRAY));
            lines.add(Component.text("Fallbacks count submitted attempts only; since reload.", NamedTextColor.GRAY));
            lines.add(Component.text("Times include transfer/wait/readback, not whole ticks.", NamedTextColor.GRAY));
            lines.add(Component.text("CPU reference is a sample, not a server speedup test.", NamedTextColor.GRAY));
            if (options.force()) lines.add(Component.text("Force mode bypasses the displayed cooldown deadlines.", NamedTextColor.YELLOW));
        } else {
            lines.add(Component.text("/gpur status detail", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/gpur status detail"))
                .append(Component.text(" | hover for explanations", NamedTextColor.GRAY))
                .hoverEvent(Component.text("Show timings, reference samples, cooldown history, UUIDs, and admission skips. Counters reset on reload.")));
        }
        return List.copyOf(lines);
    }

    private static Component workloadRow(StatusSnapshot snapshot, Options options, DeviceWorkloadStatus workload) {
        String label = workload.workload() == 1 ? "Player distances" : "Anti-Xray";
        String state;
        String explanation;
        NamedTextColor color;
        if (snapshot.stopped() || !workload.available()) {
            state = "CPU (GPU unavailable)";
            explanation = "The device is disabled or stopped. The original CPU path remains available.";
            color = NamedTextColor.RED;
        } else if (!options.gpuEnabled() || !(workload.workload() == 1 ? options.distancesEnabled() : options.antiXrayEnabled())) {
            state = "CPU (disabled in config)";
            explanation = "This GPU calculation is disabled in gpur.yml.";
            color = NamedTextColor.GRAY;
        } else if (workload.workload() == 2 && snapshot.antiXrayRejected()) {
            state = "Blocked (CPU)";
            explanation = "Anti-Xray GPU processing was blocked after a validation or preparation failure. Check the logs. This block lasts until reload.";
            color = NamedTextColor.RED;
        } else if (workload.backoffMillis() > 0 && !options.force()) {
            state = "CPU cooldown " + ((workload.backoffMillis() + 999) / 1000) + "s";
            explanation = "A sampled GPU dispatch was slower than the CPU reference. CPU is preferred for this GPU/calculation until the retry deadline.";
            color = NamedTextColor.YELLOW;
        } else {
            boolean waiting = workload.gpuResults() == 0 && workload.referenceSamples() == 0 && workload.maxDispatchNanos() == 0;
            state = waiting ? "No results yet" : "GPU enabled";
            explanation = workload.workload() == 1
                ? "FP64 player-distance queries for player lookups and natural spawning. GPU use requires enough eligible players and free capacity; movement and Mob AI stay on CPU."
                : "HIDE-mode Anti-Xray masks. GPU use requires Paper Anti-Xray enabled in the world, supported inputs, and free capacity. Other modes retain CPU processing.";
            if (waiting) explanation += " No completed GPU dispatches have been recorded since start/reload.";
            color = waiting ? NamedTextColor.GRAY : NamedTextColor.GREEN;
        }
        return row("  " + label + ": ", state, color)
            .append(Component.text(" | " + count(workload.gpuResults()) + " results", NamedTextColor.GRAY))
            .hoverEvent(Component.text(explanation + "\nResults are accepted GPU batches since start/reload, not final world or packet applications."));
    }

    private static void addWorkloadDetails(List<Component> lines, DeviceWorkloadStatus workload) {
        // A dispatch can be timed but rejected before it becomes an accepted GPU result.
        boolean dispatched = workload.referenceSamples() > 0 || workload.maxDispatchNanos() > 0;
        lines.add(row("    GPU dispatch avg / max: ", dispatched
            ? millis(workload.averageDispatchNanos()) + " / " + millis(workload.maxDispatchNanos()) + " ms" : "not recorded", NamedTextColor.WHITE));
        lines.add(row("    CPU reference avg: ", workload.referenceSamples() == 0 ? "not sampled"
            : millis(workload.averageReferenceNanos()) + " ms (" + count(workload.referenceSamples()) + " samples)", NamedTextColor.WHITE));
        lines.add(row("    CPU cooldowns: ", count(workload.backoffs()) + " | remaining: " + millis(workload.backoffMillis() * 1_000_000) + " ms", NamedTextColor.WHITE));
    }

    private static Component row(String label, String value, NamedTextColor color) {
        return Component.text(label, NamedTextColor.GRAY).append(Component.text(value, color));
    }

    private static String shortName(String name) {
        return name.replaceFirst("^(?:NVIDIA GeForce|NVIDIA|AMD)\\s+", "");
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }
}
