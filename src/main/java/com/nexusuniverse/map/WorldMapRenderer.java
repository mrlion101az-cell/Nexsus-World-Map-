package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws two layers onto the map item, both recentered every frame on the
 * VIEWING player's live position (the "player" param below is who's
 * actually looking at the map, not necessarily who's placing a POI or
 * anything like that): cached terrain pixels sourced from TerrainCache,
 * and a MapCursorCollection carrying real vanilla map icons -- rotating
 * pointer arrows for players, colored banner icons for points of interest
 * -- instead of hand-drawn colored squares.
 *
 * THROTTLING: Bukkit calls render() for every viewer roughly once per
 * server tick (20/sec) for as long as the item is being held/viewed --
 * that's not something MapRenderer itself lets you configure. Two things
 * here cut that cost, and matter for a different reason each:
 *
 *  - A per-viewer minimum interval (map.render-interval-ticks) between
 *    actual draws. This is the one that matters most: MapCanvas has no
 *    "nothing changed" concept of its own -- if render() touches the
 *    canvas at all, Bukkit sends a full map update packet to that client,
 *    whether or not the pixels actually differ from what they already
 *    have. Redrawing (and therefore re-sending) 20 times a second
 *    regardless of whether the player even moved was the actual cost,
 *    more than the pixel math itself. Returning early (touching nothing)
 *    on throttled-out ticks means no packet goes out at all that tick.
 *  - Independently, the terrain layer only gets rebuilt when the viewer's
 *    centre cell has actually changed since the last time it was drawn --
 *    with blocksPerPixel usually 16-64, a player has to walk that many
 *    blocks before the terrain layer needs to change at all, so this
 *    skips the 128x128 = 16,384-write terrain redraw on most throttled
 *    renders even when the throttle above does let a render through
 *    (e.g. to keep cursors/rotation reasonably current). Cursors are
 *    cheap by comparison and still redrawn every time the throttle
 *    allows it.
 */
public class WorldMapRenderer extends MapRenderer {

    private final NexusMapPlugin plugin;
    private final int blocksPerPixel;

    private final Map<UUID, Long> lastRenderMillis = new HashMap<>();
    private final Map<UUID, Long> lastTerrainCellKey = new HashMap<>();

    public WorldMapRenderer(NexusMapPlugin plugin, int blocksPerPixel) {
        super(true); // contextual = true -- each viewer gets their own canvas, since the map now differs per viewer
        this.plugin = plugin;
        this.blocksPerPixel = blocksPerPixel;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        // Wall-clock time, not a call counter -- render() is called once per VIEWER, so a shared
        // counter across viewers would advance faster the more viewers are online and actually
        // loosen the throttle right when it most needs to hold, instead of a stable interval.
        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(1, plugin.getConfig().getInt("map.render-interval-ticks", 4)) * 50L;

        Long last = lastRenderMillis.get(player.getUniqueId());
        if (last != null && now - last < intervalMillis) {
            return; // too soon since this viewer's last render -- touch nothing, so no packet is sent this pass
        }
        lastRenderMillis.put(player.getUniqueId(), now);

        TerrainCache cache = plugin.getTerrainCacheFor(blocksPerPixel);
        cache.ensureAreaLoaded(player);

        int centerWorldX = player.getLocation().getBlockX();
        int centerWorldZ = player.getLocation().getBlockZ();
        int centerCellX = Math.floorDiv(centerWorldX, blocksPerPixel);
        int centerCellZ = Math.floorDiv(centerWorldZ, blocksPerPixel);

        long cellKey = (((long) centerCellX) << 32) ^ (centerCellZ & 0xFFFFFFFFL);
        Long lastCellKey = lastTerrainCellKey.get(player.getUniqueId());
        if (lastCellKey == null || lastCellKey != cellKey) {
            drawTerrain(canvas, cache, centerCellX, centerCellZ);
            lastTerrainCellKey.put(player.getUniqueId(), cellKey);
        }
        drawCursors(canvas, player, centerWorldX, centerWorldZ);
    }

    private void drawTerrain(MapCanvas canvas, TerrainCache cache, int centerCellX, int centerCellZ) {
        for (int x = 0; x < 128; x++) {
            for (int z = 0; z < 128; z++) {
                int cellX = centerCellX + (x - 64);
                int cellZ = centerCellZ + (z - 64);
                canvas.setPixelColor(x, z, cache.colorAt(cellX, cellZ));
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

        // hoisted out of the loop below -- this was a config lookup (YAML tree traversal, not
        // free) run once per online player per render call before, instead of once per call
        String mapWorldName = plugin.getConfig().getString("map.world", "world");
        for (Player player : viewer.getServer().getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(mapWorldName)) {
                continue;
            }

            int[] cursorPos = toCursorCoords(
                    player.getLocation().getBlockX(), player.getLocation().getBlockZ(), centerWorldX, centerWorldZ);
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
