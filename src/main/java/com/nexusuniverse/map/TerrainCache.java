package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An open-ended, cell-indexed terrain cache -- one Color per (blocksPerPixel
 * x blocksPerPixel) world cell, keyed by cell coordinates rather than a
 * fixed 128x128 window. Coverage grows outward as players explore (the
 * same "fog of war" idea as a vanilla map), instead of being locked to one
 * static region.
 *
 * Cells are enqueued for scanning only when a player crosses into a new
 * cell (not every tick), and the scan itself is spread across ticks via
 * tick() -- getHighestBlockAt() touches chunk data and is too expensive to
 * do thousands of times in one go.
 *
 * KNOWN TRADEOFF: coverage never shrinks -- explored cells stay cached for
 * the life of the server, so memory grows with how much of the map players
 * have actually visited. Fine for most servers; if this ever becomes a
 * problem, evicting cells that are both far from every online player and
 * haven't been rescanned in a long time would be the natural next step.
 */
public class TerrainCache {

    private static final Color UNEXPLORED = Color.fromRGB(12, 12, 18); // matches the old "void/unscanned" color

    private final NexusMapPlugin plugin;
    private final int blocksPerPixel;

    private final Map<Long, Color> cellColors = new ConcurrentHashMap<>();
    private final Set<Long> queued = new HashSet<>();
    private final Deque<Long> scanQueue = new ArrayDeque<>();
    private final Map<UUID, Long> lastPlayerCell = new HashMap<>();

    // half the 128-pixel view plus a margin, so cells are ready before they scroll into frame
    private static final int LOAD_RADIUS_CELLS = 80;

    public TerrainCache(NexusMapPlugin plugin, int blocksPerPixel) {
        this.plugin = plugin;
        this.blocksPerPixel = blocksPerPixel;
    }

    public Color colorAt(int cellX, int cellZ) {
        Color color = cellColors.get(key(cellX, cellZ));
        return color != null ? color : UNEXPLORED;
    }

    /**
     * Call whenever a player views this map. Enqueues the area around them
     * for scanning if they've moved into a new cell since last checked --
     * cheap no-op otherwise, so it's safe to call from render().
     */
    public void ensureAreaLoaded(Player player) {
        int cellX = Math.floorDiv(player.getLocation().getBlockX(), blocksPerPixel);
        int cellZ = Math.floorDiv(player.getLocation().getBlockZ(), blocksPerPixel);
        long cellKey = key(cellX, cellZ);

        Long last = lastPlayerCell.get(player.getUniqueId());
        if (last != null && last == cellKey) return; // same cell as last check, nothing new to load
        lastPlayerCell.put(player.getUniqueId(), cellKey);

        for (int dx = -LOAD_RADIUS_CELLS; dx <= LOAD_RADIUS_CELLS; dx++) {
            for (int dz = -LOAD_RADIUS_CELLS; dz <= LOAD_RADIUS_CELLS; dz++) {
                long k = key(cellX + dx, cellZ + dz);
                if (cellColors.containsKey(k) || queued.contains(k)) continue;
                queued.add(k);
                scanQueue.add(k);
            }
        }
    }

    /** Re-enqueues every currently-known cell for a fresh scan (picks up new builds, terrain changes, etc.). */
    public void requeueAllForRefresh() {
        for (Long k : cellColors.keySet()) {
            if (queued.add(k)) {
                scanQueue.add(k);
            }
        }
    }

    public boolean hasPendingWork() {
        return !scanQueue.isEmpty();
    }

    /** Call once per server tick. Scans a bounded batch (map.pixels-per-tick from config) off the queue. */
    public void tick(World world) {
        if (scanQueue.isEmpty()) return;

        int perTick = plugin.getConfig().getInt("map.pixels-per-tick", 64);
        int processed = 0;

        while (processed < perTick && !scanQueue.isEmpty()) {
            long k = scanQueue.poll();
            queued.remove(k);

            int[] cell = unkey(k);
            int worldX = cell[0] * blocksPerPixel;
            int worldZ = cell[1] * blocksPerPixel;

            cellColors.put(k, sampleColor(world, worldX, worldZ));
            processed++;
        }
    }

    private Color sampleColor(World world, int x, int z) {
        Block block = world.getHighestBlockAt(x, z);
        Color base = colorFor(block.getType());
        return shadeByElevation(base, block.getY());
    }

    /**
     * Darkens/lightens a base terrain color relative to sea level (y=64) --
     * gives a rough sense of hills/valleys without real shadow-casting.
     * (Previously this compared against a single sampled "baseline" point,
     * which doesn't make sense anymore now that the cache spans a
     * potentially huge area rather than one fixed region -- a fixed sea-level
     * reference works uniformly everywhere instead.)
     */
    private Color shadeByElevation(Color base, int y) {
        int delta = y - 64;
        double factor = 1.0 + Math.max(-0.35, Math.min(0.35, delta * 0.015));

        int r = clamp((int) (base.getRed() * factor));
        int g = clamp((int) (base.getGreen() * factor));
        int b = clamp((int) (base.getBlue() * factor));
        return Color.fromRGB(r, g, b);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private long key(int cellX, int cellZ) {
        return (((long) cellX) << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private int[] unkey(long k) {
        int cellX = (int) (k >> 32);
        int cellZ = (int) k;
        return new int[]{cellX, cellZ};
    }

    /**
     * Material-to-color mapping. Wider coverage than a first pass --
     * includes ores, concrete/terracotta/wool families, crops, coral,
     * and ice variants on top of the basic terrain categories. Still
     * approximate, not a pixel-perfect vanilla map palette, but should
     * read as genuinely varied terrain rather than a handful of flat
     * blobs.
     */
    private Color colorFor(Material material) {
        String name = material.name();

        if (name.contains("WATER")) return Color.fromRGB(55, 95, 195);
        if (name.contains("LAVA")) return Color.fromRGB(210, 90, 20);
        if (name.contains("MAGMA")) return Color.fromRGB(150, 60, 20);

        if (name.contains("SAND") && !name.contains("SANDSTONE")) return Color.fromRGB(225, 215, 155);
        if (name.contains("SANDSTONE")) return Color.fromRGB(215, 200, 145);
        if (name.contains("RED_SAND")) return Color.fromRGB(190, 110, 55);

        if (name.contains("SNOW") || name.contains("POWDER_SNOW")) return Color.fromRGB(245, 245, 250);
        if (name.contains("ICE")) return Color.fromRGB(160, 200, 235);

        if (name.contains("LEAVES") || name.contains("VINE") || name.contains("MOSS")) {
            return Color.fromRGB(65, 130, 55);
        }
        if (name.contains("GRASS") || name.contains("FERN")) return Color.fromRGB(85, 150, 65);
        if (name.contains("MUSHROOM")) return Color.fromRGB(150, 90, 70);

        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANKS") || name.contains("FENCE")) {
            return Color.fromRGB(130, 95, 60);
        }

        if (name.contains("DEEPSLATE")) return Color.fromRGB(75, 75, 80);
        if (name.contains("STONE") || name.contains("ROCK") || name.contains("COBBLE") || name.contains("BASALT")) {
            return Color.fromRGB(125, 125, 125);
        }
        if (name.contains("GRANITE")) return Color.fromRGB(150, 105, 90);
        if (name.contains("DIORITE")) return Color.fromRGB(200, 200, 200);
        if (name.contains("ANDESITE")) return Color.fromRGB(135, 135, 135);

        if (name.contains("ORE")) {
            if (name.contains("GOLD")) return Color.fromRGB(230, 200, 60);
            if (name.contains("IRON")) return Color.fromRGB(200, 170, 150);
            if (name.contains("DIAMOND") || name.contains("EMERALD")) return Color.fromRGB(80, 210, 190);
            if (name.contains("REDSTONE")) return Color.fromRGB(200, 40, 40);
            if (name.contains("COAL")) return Color.fromRGB(60, 60, 60);
            return Color.fromRGB(150, 150, 160);
        }

        if (name.contains("TERRACOTTA")) return Color.fromRGB(160, 100, 75);
        if (name.contains("CONCRETE")) return Color.fromRGB(170, 170, 175);
        if (name.contains("WOOL") || name.contains("CARPET")) return Color.fromRGB(210, 210, 210);

        if (name.contains("DIRT") || name.contains("PATH") || name.contains("FARMLAND") || name.contains("MUD")) {
            return Color.fromRGB(115, 90, 60);
        }

        if (name.contains("WHEAT") || name.contains("CARROT") || name.contains("POTATO") || name.contains("CROP")) {
            return Color.fromRGB(190, 175, 70);
        }

        if (name.contains("CORAL")) return Color.fromRGB(220, 100, 140);

        if (name.contains("NETHERRACK") || name.contains("NETHER_WART")) return Color.fromRGB(105, 45, 45);
        if (name.contains("SOUL_SAND") || name.contains("SOUL_SOIL")) return Color.fromRGB(80, 65, 55);
        if (name.contains("GLOWSTONE")) return Color.fromRGB(220, 190, 100);

        if (name.contains("END_STONE") || name.contains("CHORUS") || name.contains("PURPUR")) {
            return Color.fromRGB(215, 210, 165);
        }

        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return Color.fromRGB(12, 12, 18);
        }

        return Color.fromRGB(150, 150, 150);
    }
}
