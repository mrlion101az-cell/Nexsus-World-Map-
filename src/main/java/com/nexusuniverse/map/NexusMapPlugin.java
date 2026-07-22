package com.nexusuniverse.map;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NexusMapPlugin extends JavaPlugin {

    private List<Integer> zoomLevels;
    private final Map<Integer, MapView> mapViewsByScale = new HashMap<>();
    private final Map<Integer, WorldMapRenderer> renderersByScale = new HashMap<>();
    private final Map<Integer, TerrainCache> terrainCachesByScale = new HashMap<>();

    private POIManager poiManager;
    private MapItems mapItems;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.zoomLevels = getConfig().getIntegerList("map.zoom-levels");
        if (zoomLevels.isEmpty()) {
            zoomLevels = new ArrayList<>(List.of(16, 32, 64));
        }

        this.poiManager = new POIManager(this);
        this.mapItems = new MapItems(this);

        setupMapViews();

        getCommand("nexusmap").setExecutor(new NexusMapCommand(this));
        getServer().getPluginManager().registerEvents(new ZoomCycleListener(this), this);

        // spread terrain scanning across ticks, for every zoom level, so
        // a refresh doesn't spike the server
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (int scale : zoomLevels) {
                TerrainCache cache = terrainCachesByScale.get(scale);
                boolean justFinished = cache.refreshTick();
                if (justFinished) {
                    renderersByScale.get(scale).markTerrainDirty();
                    getLogger().info("NexusMap terrain refresh complete for " + scale + " blocks/pixel.");
                }
            }
        }, 1L, 1L);

        // kick off the first refresh shortly after startup, then on the
        // configured interval, for every zoom level
        int intervalTicks = getConfig().getInt("map.refresh-interval-minutes", 10) * 60 * 20;
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (TerrainCache cache : terrainCachesByScale.values()) {
                cache.startRefresh();
            }
        }, 100L, intervalTicks);

        getLogger().info("NexusMap enabled with zoom levels: " + zoomLevels);
    }

    private void setupMapViews() {
        String worldName = getConfig().getString("map.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("NexusMap: world '" + worldName + "' not found -- "
                    + "falling back to the server's default world for the MapView.");
            world = Bukkit.getWorlds().get(0);
        }

        for (int scale : zoomLevels) {
            MapView view = Bukkit.createMap(world);
            view.getRenderers().forEach(view::removeRenderer);

            WorldMapRenderer renderer = new WorldMapRenderer(this, scale);
            view.addRenderer(renderer);
            view.setUnlimitedTracking(false);
            view.setLocked(true);

            mapViewsByScale.put(scale, view);
            renderersByScale.put(scale, renderer);
            terrainCachesByScale.put(scale, new TerrainCache(this, scale));
        }
    }

    public List<Integer> getZoomLevels() {
        return zoomLevels;
    }

    public MapView getMapViewFor(int blocksPerPixel) {
        return mapViewsByScale.get(blocksPerPixel);
    }

    public TerrainCache getTerrainCacheFor(int blocksPerPixel) {
        return terrainCachesByScale.get(blocksPerPixel);
    }

    public POIManager getPoiManager() {
        return poiManager;
    }

    public MapItems getMapItems() {
        return mapItems;
    }
}
