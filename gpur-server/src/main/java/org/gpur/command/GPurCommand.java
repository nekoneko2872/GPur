package org.gpur.command;

import java.util.List;
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
        super("gpur", "GPur runtime diagnostics", "/gpur [status|reload]", List.of("gpurpur"));
        this.setPermission("gpur.command");
        if (Bukkit.getPluginManager().getPermission("gpur.command") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("gpur.command", PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!this.testPermission(sender)) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            GPurServices.reload(MinecraftServer.getServer());
            sender.sendMessage("GPur configuration reloaded; devices revalidated.");
        } else if (args.length > 0 && !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(this.getUsage());
            return true;
        }
        ComputeService service = GPurServices.compute();
        sender.sendMessage("GPur 26.2, configuration schema " + GPurConfig.version);
        sender.sendMessage(service == null ? "Compute service stopped; CPU fallback." : service.status());
        sender.sendMessage("GPU: verified FP64 distances and HIDE Anti-Xray masks. Plugin/event execution: CPU.");
        sender.sendMessage("Terrain, dependent redstone updates, and Mob AI remain on their original CPU paths.");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!sender.hasPermission("gpur.command") || args.length != 1) return List.of();
        return List.of("status", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
    }
}
