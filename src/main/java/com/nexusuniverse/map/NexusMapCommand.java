package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class NexusMapCommand implements CommandExecutor {

    private final NexusMapPlugin plugin;

    public NexusMapCommand(NexusMapPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> {
                player.getInventory().addItem(plugin.getMapItems().create());
                player.sendMessage("§aGave you the server map.");
            }
            case "addpoi" -> handleAddPoi(player, args);
            case "removepoi" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /nexusmap removepoi <name>");
                    return true;
                }
                boolean removed = plugin.getPoiManager().remove(args[1]);
                player.sendMessage(removed ? "§aRemoved POI \"" + args[1] + "\"." : "§cNo POI named \"" + args[1] + "\".");
            }
            case "listpoi" -> {
                player.sendMessage("§ePoints of interest:");
                plugin.getPoiManager().all().forEach(p ->
                        player.sendMessage("§7 - §f" + p.getName() + " §7(" + p.getX() + ", " + p.getZ() + ")"));
            }
            case "refresh" -> {
                if (!player.hasPermission("nexusmap.admin")) {
                    player.sendMessage("§cNo permission.");
                    return true;
                }
                for (int scale : plugin.getZoomLevels()) {
                    plugin.getTerrainCacheFor(scale).requeueAllForRefresh();
                }
                player.sendMessage("§aTerrain refresh started for all zoom levels -- spread over the next several seconds.");
            }
            default -> sendUsage(player);
        }
        return true;
    }

    private void handleAddPoi(Player player, String[] args) {
        if (!player.hasPermission("nexusmap.admin")) {
            player.sendMessage("§cNo permission.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /nexusmap addpoi <name> [color: red|blue|green|yellow|purple]");
            return;
        }

        Color color = switch (args.length >= 3 ? args[2].toLowerCase() : "red") {
            case "blue" -> Color.fromRGB(50, 100, 220);
            case "green" -> Color.fromRGB(50, 200, 80);
            case "yellow" -> Color.fromRGB(230, 210, 40);
            case "purple" -> Color.fromRGB(160, 60, 200);
            default -> Color.fromRGB(220, 40, 40);
        };

        plugin.getPoiManager().add(args[1], player.getLocation(), color);
        player.sendMessage("§aAdded POI \"" + args[1] + "\" at your location.");
    }

    private void sendUsage(Player player) {
        player.sendMessage("§7/nexusmap give");
        player.sendMessage("§7/nexusmap addpoi <name> [color] §8(admin)");
        player.sendMessage("§7/nexusmap removepoi <name> §8(admin)");
        player.sendMessage("§7/nexusmap listpoi");
        player.sendMessage("§7/nexusmap refresh §8(admin)");
    }
}
