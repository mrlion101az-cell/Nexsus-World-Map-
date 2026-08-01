package com.nexusuniverse.map;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;

public class MapItems {

    private final NexusMapPlugin plugin;
    private final NamespacedKey zoomKey;

    public MapItems(NexusMapPlugin plugin) {
        this.plugin = plugin;
        this.zoomKey = new NamespacedKey(plugin, "nexusmap_blocks_per_pixel");
    }

    /** Creates a map item at the plugin's configured default zoom level. */
    public ItemStack create() {
        int defaultIndex = plugin.getConfig().getInt("map.default-zoom-index", 0);
        var levels = plugin.getZoomLevels();
        int scale = levels.get(Math.min(defaultIndex, levels.size() - 1));
        return createAtScale(scale);
    }

    public ItemStack createAtScale(int blocksPerPixel) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(plugin.getMapViewFor(blocksPerPixel));
        meta.setDisplayName("§eNexus Server Map §7(" + blocksPerPixel + " blk/px)");
        meta.setLore(java.util.List.of(
                "§7A live overview of the server.",
                "§7Right-click to change zoom."
        ));
        meta.getPersistentDataContainer().set(zoomKey, PersistentDataType.INTEGER, blocksPerPixel);
        item.setItemMeta(meta);
        return item;
    }

    /** Reads which zoom level (blocks-per-pixel) a held item represents, or -1 if it's not one of ours. */
    public int readScale(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        Integer value = item.getItemMeta().getPersistentDataContainer().get(zoomKey, PersistentDataType.INTEGER);
        return value != null ? value : -1;
    }
}
