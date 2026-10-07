package org.gpur.command;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.gpur.GPurConfig;
import org.gpur.GPurServices;
import org.gpur.compute.ComputeService;

public final class GPurCommand extends Command {
    public GPurCommand() {
        super("gpur", "GPur runtime diagnostics", "/gpur [status [detail]|reload]", List.of("gpurpur"));
        this.setPermission("gpur.command");
        if (Bukkit.getPluginManager().getPermission("gpur.command") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("gpur.command", PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!this.testPermission(sender)) return true;
        boolean reload = args.length == 1 && args[0].equalsIgnoreCase("reload");
        boolean detail = args.length == 2 && args[0].equalsIgnoreCase("status") && args[1].equalsIgnoreCase("detail");
        boolean status = args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("status") || detail;
        if (!reload && !status) {
            sender.sendMessage(Component.text(this.getUsage(), NamedTextColor.YELLOW));
            return true;
        }
        if (reload) {
            GPurServices.reload(MinecraftServer.getServer());
            sender.sendMessage(Component.text("GPur reloaded; GPU devices rechecked and counters reset.", NamedTextColor.GREEN));
        }
        ComputeService service = GPurServices.compute();
        GPurStatusDisplay.Options options = new GPurStatusDisplay.Options(GPurConfig.gpuAccelerationEnabled,
            GPurConfig.gpuForce, GPurConfig.playersGpuEnabled || GPurConfig.mobSpawnGpuEnabled, GPurConfig.antiXrayGpuEnabled,
            GPurConfig.preloadingEnabled, GPurConfig.preloadMaxExtraDistance, GPurConfig.elytraThroughputBoostEnabled);
        GPurStatusDisplay.render(service == null ? null : service.statusSnapshot(), options, detail).forEach(sender::sendMessage);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!sender.hasPermission("gpur.command")) return List.of();
        if (args.length == 1) {
            return List.of("status", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")
                && "detail".startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) return List.of("detail");
        return List.of();
    }
}
