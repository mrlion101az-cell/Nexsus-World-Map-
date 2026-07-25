package com.nexusuniverse.map;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ZoomCycleListener implements Listener {

    private final NexusMapPlugin plugin;

    public ZoomCycleListener(NexusMapPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        int currentScale = plugin.getMapItems().readScale(item);
        if (currentScale == -1) return; // not one of our map items

        event.setCancelled(true);

        List<Integer> levels = plugin.getZoomLevels();
        int currentIndex = levels.indexOf(currentScale);
        int nextIndex = (currentIndex + 1) % levels.size();
        int nextScale = levels.get(nextIndex);

        Player player = event.getPlayer();
        player.getInventory().setItemInMainHand(plugin.getMapItems().createAtScale(nextScale));
        player.sendMessage("§eZoom: §f" + nextScale + " blocks/pixel");
    }
}
