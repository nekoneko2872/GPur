package org.gpur.command;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.gpur.GPurConfig;
import org.gpur.GPurServices;
import org.gpur.generation.GPurGenerationStatusSnapshot;
import org.gpur.preload.GPurPreloadStatusSnapshot;
import org.purpurmc.purpur.PurpurConfig;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import io.papermc.paper.configuration.type.EngineMode;

public final class GPurCommand extends Command {
    public GPurCommand(final String name) {
        super(name);
        this.description = "GPur related commands";
        this.usageMessage = "/gpur [status | reload | version]";
        this.setPermission("bukkit.command.purpur");
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location)
        throws IllegalArgumentException {
        if (args.length == 1) {
            return Stream.of("status", "reload", "version")
                .filter(arg -> arg.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        final String subcommand = args.length == 0 ? "status" : args[0].toLowerCase();
        return switch (subcommand) {
            case "status" -> {
                this.sendStatus(sender);
                yield true;
            }
            case "reload" -> {
                this.reloadConfig(sender);
                yield true;
            }
            case "version" -> {
                final Command versionCommand = org.bukkit.Bukkit.getServer().getCommandMap().getCommand("version");
                yield versionCommand != null && versionCommand.execute(sender, commandLabel, new String[0]);
            }
            default -> {
                sender.sendMessage(Component.text("Usage: " + this.usageMessage, NamedTextColor.RED));
                yield false;
            }
        };
    }

    private void sendStatus(final CommandSender sender) {
        final GPurGenerationStatusSnapshot generation = GPurServices.generation().statusSnapshot();
        final GPurPreloadStatusSnapshot preload = GPurServices.preload().statusSnapshot();
        final MinecraftServer server = MinecraftServer.getServer();
        int paperAntiXrayEnabledWorlds = 0;
        int gpuCompatibleAntiXrayWorlds = 0;
        int loadedWorlds = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            ++loadedWorlds;
            if (!level.paperConfig().anticheat.antiXray.enabled) {
                continue;
            }

            ++paperAntiXrayEnabledWorlds;
            if (level.paperConfig().anticheat.antiXray.engineMode == EngineMode.HIDE || GPurConfig.antiXrayGpuAllowInexactResults) {
                ++gpuCompatibleAntiXrayWorlds;
            }
        }

        sender.sendMessage(Component.text("GPur Status", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
            "Mode: " + generation.mode() + " | Device: " + generation.deviceName(),
            NamedTextColor.YELLOW
        ));
        sender.sendMessage(Component.text("Backend Detail: " + generation.backendDetail(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
            "Turbo: " + onOff(generation.turboModeEnabled())
                + " | Terrain Assist: " + onOff(generation.terrainAssistEnabled())
                + " | Assist Gate: " + (generation.terrainAssistGateOpen() ? "open" : "closed")
                + " | Active Noise: " + generation.activeNoiseTasks() + "/" + generation.terrainAssistActiveNoiseThreshold()
                + " (cfg " + GPurConfig.terrainGpuHeavyLoadMinActiveNoiseTasks + ")"
                + " | Assist Contexts: " + generation.busyExecutionContexts() + "/" + generation.totalExecutionContexts()
                + " | Assist In-Flight: " + generation.terrainAssistInFlight() + "/" + generation.terrainAssistInFlightLimit()
                + " | Assist Pressure: " + generation.terrainAssistPreloadPressure(),
            NamedTextColor.YELLOW
        ));
        sender.sendMessage(Component.text(
            "GPU Totals: terrain assist dispatches=" + generation.completedTerrainAssistDispatches()
                + ", terrain assist columns=" + generation.completedTerrainAssistColumns()
                + ", terrain assist avg=" + generation.averageTerrainAssistMicrosPerColumn() + " us/column"
                + ", anti-xray sections=" + generation.completedAntiXraySections()
                + ", structure scans=" + generation.completedStructureScans()
                + ", mob candidates=" + generation.completedMobScanCandidates(),
            NamedTextColor.YELLOW
        ));
        sender.sendMessage(Component.text(
            "Anti-Xray: GPur=" + onOff(GPurConfig.antiXrayGpuEnabled)
                + " | Paper worlds=" + paperAntiXrayEnabledWorlds + "/" + loadedWorlds
                + " | GPU-compatible worlds=" + gpuCompatibleAntiXrayWorlds + "/" + loadedWorlds,
            NamedTextColor.YELLOW
        ));
        sender.sendMessage(Component.text(
            "Preload: anchors=" + preload.activeAnchors()
                + " across " + preload.activeWorlds() + " world(s)"
                + ", shared selections=" + preload.sharedSelections()
                + ", shared hits=" + preload.sharedPriorityHits()
                + ", retained hits=" + preload.retainedPriorityHits(),
            NamedTextColor.AQUA
        ));
        sender.sendMessage(Component.text(
            "Send Burst: total=" + preload.sendBurstSends() + ", turbo=" + preload.turboSendBurstSends(),
            NamedTextColor.AQUA
        ));
    }

    private void reloadConfig(final CommandSender sender) {
        Command.broadcastCommandMessage(
            sender,
            Component.text("Please note that this command is not supported and may cause issues", NamedTextColor.RED)
        );
        Command.broadcastCommandMessage(
            sender,
            Component.text("If you encounter any issues please use the /stop command to restart your server.", NamedTextColor.RED)
        );

        final MinecraftServer console = MinecraftServer.getServer();
        PurpurConfig.init((File)console.options.valueOf("purpur-settings"));
        GPurConfig.init((File)console.options.valueOf("gpur-settings"));
        GPurServices.reload(console);
        for (final ServerLevel level : console.getAllLevels()) {
            level.purpurConfig.init();
            level.resetBreedingCooldowns();
        }
        console.server.reloadCount++;

        Command.broadcastCommandMessage(sender, Component.text("GPur config reload complete.", NamedTextColor.GREEN));
    }

    private static String onOff(final boolean enabled) {
        return enabled ? "on" : "off";
    }
}
