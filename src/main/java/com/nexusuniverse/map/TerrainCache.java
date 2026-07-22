package com.nexusuniverse.map;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Scans the world in a 128x128 grid (each cell = blocksPerPixel blocks)
 * around a center point and caches an approximate top-down color per
 * cell, with light elevation shading for a pseudo-relief look. One
 * instance per zoom level -- each zoom owns its own cache, center, and
 * scale, refreshed independently.
 *
 * The scan is spread across many ticks (see refreshTick()) rather than
 * done in one go, since getHighestBlockAt() touches chunk data and can
 * be expensive/laggy if done 16,384 times in a single tick, especially
 * on unloaded chunks.
 */
public class TerrainCache {

    private final NexusMapPlugin plugin;
    private final int blocksPerPixel;
    private final Color[][] grid = new Color[128][128];
    private boolean refreshing = false;
    private int scanIndex = 0;

    // running baseline for elevation shading, recalculated per refresh
    private int baselineY = 64;

    public TerrainCache(NexusMapPlugin plugin, int blocksPerPixel) {
        this.plugin = plugin;
        this.blocksPerPixel = blocksPerPixel;
        for (Color[] row : grid) {
            java.util.Arrays.fill(row, Color.GRAY);
        }
    }

    public Color colorAt(int pixelX, int pixelZ) {
        if (pixelX < 0 || pixelX > 127 || pixelZ < 0 || pixelZ > 127) return Color.BLACK;
        return grid[pixelX][pixelZ];
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    public void startRefresh() {
        if (refreshing) return;
        refreshing = true;
        scanIndex = 0;
    }

    /**
     * Call once per server tick. Scans a batch of pixels
     * (map.pixels-per-tick from config) and returns true when a full
     * refresh has just completed.
     */
    public boolean refreshTick() {
        if (!refreshing) return false;

        World world = Bukkit.getWorld(plugin.getConfig().getString("map.world", "world"));
        if (world == null) {
            refreshing = false;
            plugin.getLogger().warning("NexusMap: configured world not found, refresh aborted.");
            return false;
        }

        int centerX = plugin.getConfig().getInt("map.center-x", 0);
        int centerZ = plugin.getConfig().getInt("map.center-z", 0);
        int perTick = plugin.getConfig().getInt("map.pixels-per-tick", 64);

        if (scanIndex == 0) {
            // sample the center point once per refresh as our elevation baseline
            baselineY = world.getHighestBlockAt(centerX, centerZ).getY();
        }

        int processed = 0;
        while (processed < perTick && scanIndex < 128 * 128) {
            int px = scanIndex / 128;
            int pz = scanIndex % 128;

            int worldX = centerX + (px - 64) * blocksPerPixel;
            int worldZ = centerZ + (pz - 64) * blocksPerPixel;

            grid[px][pz] = sampleColor(world, worldX, worldZ);

            scanIndex++;
            processed++;
        }

        if (scanIndex >= 128 * 128) {
            refreshing = false;
            return true;
        }
        return false;
    }

    private Color sampleColor(World world, int x, int z) {
        Block block = world.getHighestBlockAt(x, z);
        Color base = colorFor(block.getType());
        return shadeByElevation(base, block.getY());
    }

    /**
     * Darkens/lightens a base terrain color based on how far its block
     * is from the sampled baseline height -- gives a rough sense of
     * hills/valleys without needing real shadow-casting.
     */
    private Color shadeByElevation(Color base, int y) {
        int delta = y - baselineY;
        double factor = 1.0 + Math.max(-0.35, Math.min(0.35, delta * 0.015));

        int r = clamp((int) (base.getRed() * factor));
        int g = clamp((int) (base.getGreen() * factor));
        int b = clamp((int) (base.getBlue() * factor));
        return Color.fromRGB(r, g, b);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
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
