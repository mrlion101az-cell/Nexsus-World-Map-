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
    private final Map<Integer, TerrainCache> terrainCachesByScale = new HashMap<>();

    private POIManager poiManager;
    private MapItems mapItems;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // saveDefaultConfig() only writes config.yml if it's completely missing -- on every
        // other startup (i.e. every update) it leaves an existing file untouched, so a new
        // setting an update adds (like map.render-interval-ticks) would otherwise never reach a
        // server that already has a config.yml on disk. copyDefaults(true) + saveConfig() merges
        // in any keys the file is missing while leaving every value already set alone.
        getConfig().options().copyDefaults(true);
        saveConfig();

        this.zoomLevels = getConfig().getIntegerList("map.zoom-levels");
        if (zoomLevels.isEmpty()) {
            zoomLevels = new ArrayList<>(List.of(16, 32, 64));
        }

        this.poiManager = new POIManager(this);
        this.mapItems = new MapItems(this);

        setupMapViews();

        getCommand("nexusmap").setExecutor(new NexusMapCommand(this));
        getServer().getPluginManager().registerEvents(new ZoomCycleListener(this), this);

        // Spread terrain scanning across ticks, for every zoom level, so it
        // never spikes the server. Each cache queues its own work as players
        // explore into new cells (see TerrainCache.ensureAreaLoaded, called
        // from WorldMapRenderer.render()) -- this loop just works through
        // that queue at a steady pace.
        getServer().getScheduler().runTaskTimer(this, () -> {
            World world = resolveWorld();
            if (world == null) return;
            for (TerrainCache cache : terrainCachesByScale.values()) {
                cache.tick(world);
            }
        }, 1L, 1L);

        // Periodically re-queue everything already explored so far for a
        // fresh scan, so builds/terrain changes eventually show up on the
        // map without needing a manual /nexusmap refresh.
        int intervalTicks = getConfig().getInt("map.refresh-interval-minutes", 10) * 60 * 20;
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (TerrainCache cache : terrainCachesByScale.values()) {
                cache.requeueAllForRefresh();
            }
        }, intervalTicks, intervalTicks);

        getLogger().info("NexusMap enabled with zoom levels: " + zoomLevels);
    }

    private void setupMapViews() {
        World world = resolveWorld();

        for (int scale : zoomLevels) {
            MapView view = Bukkit.createMap(world);
            view.getRenderers().forEach(view::removeRenderer);

            WorldMapRenderer renderer = new WorldMapRenderer(this, scale);
            view.addRenderer(renderer);
            view.setUnlimitedTracking(false);
            view.setLocked(true);

            mapViewsByScale.put(scale, view);
            terrainCachesByScale.put(scale, new TerrainCache(this, scale));
        }
    }

    private World resolveWorld() {
        String worldName = getConfig().getString("map.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("NexusMap: world '" + worldName + "' not found -- "
                    + "falling back to the server's default world.");
            world = Bukkit.getWorlds().get(0);
        }
        return world;
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
