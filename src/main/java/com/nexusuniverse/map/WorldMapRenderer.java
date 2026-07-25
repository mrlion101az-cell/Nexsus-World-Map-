package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Draws two layers onto the map item, both recentered every frame on the
 * VIEWING player's live position (the "player" param below is who's
 * actually looking at the map, not necessarily who's placing a POI or
 * anything like that): cached terrain pixels sourced from TerrainCache,
 * and a MapCursorCollection carrying real vanilla map icons -- rotating
 * pointer arrows for players, colored banner icons for points of interest
 * -- instead of hand-drawn colored squares.
 *
 * Because content now differs per viewer (each player's own copy of the
 * map is centered on themselves), every render() call redraws the full
 * 128x128 terrain layer -- there's no "only redraw when stale" shortcut
 * anymore the way there was for a single fixed region. That's still cheap:
 * it's 16,384 canvas writes from an already-cached color lookup, not new
 * world scanning (the actual expensive part stays throttled in
 * TerrainCache.tick(), decoupled entirely from render()).
 */
public class WorldMapRenderer extends MapRenderer {

    private final NexusMapPlugin plugin;
    private final int blocksPerPixel;

    public WorldMapRenderer(NexusMapPlugin plugin, int blocksPerPixel) {
        super(true); // contextual = true -- each viewer gets their own canvas, since the map now differs per viewer
        this.plugin = plugin;
        this.blocksPerPixel = blocksPerPixel;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        TerrainCache cache = plugin.getTerrainCacheFor(blocksPerPixel);
        cache.ensureAreaLoaded(player);

        int centerWorldX = player.getLocation().getBlockX();
        int centerWorldZ = player.getLocation().getBlockZ();
        int centerCellX = Math.floorDiv(centerWorldX, blocksPerPixel);
        int centerCellZ = Math.floorDiv(centerWorldZ, blocksPerPixel);

        drawTerrain(canvas, cache, centerCellX, centerCellZ);
        drawCursors(canvas, player, centerWorldX, centerWorldZ);
    }

    private void drawTerrain(MapCanvas canvas, TerrainCache cache, int centerCellX, int centerCellZ) {
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                int cellX = centerCellX + (x - 64);
                int cellZ = centerCellZ + (z - 64);
                Color bukkitColor = cache.colorAt(cellX, cellZ);
                canvas.setPixelColor(x, z, new java.awt.Color(
                        bukkitColor.getRed(), bukkitColor.getGreen(), bukkitColor.getBlue()));
            }
        }
    }

    private void drawCursors(MapCanvas canvas, Player viewer, int centerWorldX, int centerWorldZ) {
        MapCursorCollection cursors = canvas.getCursors();
        // clear and rebuild each render -- cheap, this is just icon
        // placement, not pixel work
        while (cursors.size() > 0) {
            cursors.removeCursor(cursors.getCursor(0));
        }

        for (PointOfInterest poi : plugin.getPoiManager().all()) {
            int[] cursorPos = toCursorCoords(poi.getX(), poi.getZ(), centerWorldX, centerWorldZ);
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
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ(), centerWorldX, centerWorldZ);
            if (cursorPos == null) continue;

            byte direction = yawToDirection(player.getLocation().getYaw());
            boolean isViewer = player.getUniqueId().equals(viewer.getUniqueId());

            MapCursor.Type type = isViewer ? MapCursor.Type.PLAYER : MapCursor.Type.BLUE_MARKER;
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
        if (color.equals(Color.fromRGB(50, 100, 220))) return MapCursor.Type.BANNER_BLUE;
        if (color.equals(Color.fromRGB(50, 200, 80))) return MapCursor.Type.BANNER_GREEN;
        if (color.equals(Color.fromRGB(230, 210, 40))) return MapCursor.Type.BANNER_YELLOW;
        if (color.equals(Color.fromRGB(160, 60, 200))) return MapCursor.Type.BANNER_PURPLE;
        return MapCursor.Type.BANNER_RED;
    }
}
