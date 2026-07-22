package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Draws two layers onto the map item: cached terrain pixels (redrawn
 * only when the terrain cache actually changes), and a MapCursorCollection
 * carrying real vanilla map icons -- rotating pointer arrows for players,
 * colored banner icons for points of interest -- instead of hand-drawn
 * colored squares. This is what makes it read as a polished vanilla map
 * rather than a custom overlay: the client renders these icons natively.
 */
public class WorldMapRenderer extends MapRenderer {

    private final NexusMapPlugin plugin;
    private final int blocksPerPixel;
    private boolean terrainDrawn = false;

    public WorldMapRenderer(NexusMapPlugin plugin, int blocksPerPixel) {
        super(true); // contextual = true, we manage our own redraw timing
        this.plugin = plugin;
        this.blocksPerPixel = blocksPerPixel;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        TerrainCache cache = plugin.getTerrainCacheFor(blocksPerPixel);

        if (!terrainDrawn || cache.isRefreshing()) {
            drawTerrain(canvas, cache);
            if (!cache.isRefreshing()) {
                terrainDrawn = true;
            }
        }

        drawCursors(canvas, player);
    }

    /** Call this after a terrain refresh completes to force a redraw next render(). */
    public void markTerrainDirty() {
        terrainDrawn = false;
    }

    private void drawTerrain(MapCanvas canvas, TerrainCache cache) {
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                canvas.setPixelColor(x, z, cache.colorAt(x, z));
            }
        }
    }

    private void drawCursors(MapCanvas canvas, Player viewer) {
        MapCursorCollection cursors = canvas.getCursors();
        // clear and rebuild each render -- cheap, this is just icon
        // placement, not pixel work
        while (cursors.size() > 0) {
            cursors.removeCursor(cursors.getCursor(0));
        }

        int centerX = plugin.getConfig().getInt("map.center-x", 0);
        int centerZ = plugin.getConfig().getInt("map.center-z", 0);

        for (PointOfInterest poi : plugin.getPoiManager().all()) {
            int[] cursorPos = toCursorCoords(poi.getX(), poi.getZ(), centerX, centerZ);
            if (cursorPos == null) continue;

            MapCursor.Type type = bannerTypeFor(poi.getColor());
            cursors.addCursor(new MapCursor(
                    (byte) cursorPos[0], (byte) cursorPos[1], (byte) 8, type, true));
        }

        for (Player player : viewer.getServer().getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(plugin.getConfig().getString("map.world", "world"))) {
                continue;
            }

            int[] cursorPos = toCursorCoords(
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ(), centerX, centerZ);
            if (cursorPos == null) continue;

            byte direction = yawToDirection(player.getLocation().getYaw());
            boolean isViewer = player.getUniqueId().equals(viewer.getUniqueId());

            MapCursor.Type type = isViewer ? MapCursor.Type.PLAYER : MapCursor.Type.BLUE_POINTER;
            cursors.addCursor(new MapCursor(
                    (byte) cursorPos[0], (byte) cursorPos[1], direction, type, true));
        }
    }

    /**
     * Converts world coordinates to MapCursor's internal -128..127
     * coordinate space (double the resolution of the 128 visible
     * pixels), returning null if the point falls outside the map.
     */
    private int[] toCursorCoords(int worldX, int worldZ, int centerX, int centerZ) {
        double pixelX = 64 + (worldX - centerX) / (double) blocksPerPixel;
        double pixelZ = 64 + (worldZ - centerZ) / (double) blocksPerPixel;

        int cursorX = (int) Math.round((pixelX * 2) - 128);
        int cursorZ = (int) Math.round((pixelZ * 2) - 128);

        if (cursorX < -128 || cursorX > 127 || cursorZ < -128 || cursorZ > 127) return null;
        return new int[] { cursorX, cursorZ };
    }

    /** Minecraft cursor direction is 0-15, one step per 22.5 degrees. */
    private byte yawToDirection(float yaw) {
        float normalized = yaw % 360;
        if (normalized < 0) normalized += 360;
        int step = Math.round(normalized / 22.5f) & 15;
        return (byte) step;
    }

    private MapCursor.Type bannerTypeFor(Color color) {
        // matches the color choices offered by /nexusmap addpoi
        if (color.equals(Color.fromRGB(50, 100, 220))) return MapCursor.Type.BLUE_BANNER;
        if (color.equals(Color.fromRGB(50, 200, 80))) return MapCursor.Type.GREEN_BANNER;
        if (color.equals(Color.fromRGB(230, 210, 40))) return MapCursor.Type.YELLOW_BANNER;
        if (color.equals(Color.fromRGB(160, 60, 200))) return MapCursor.Type.PURPLE_BANNER;
        return MapCursor.Type.RED_BANNER;
    }
}
