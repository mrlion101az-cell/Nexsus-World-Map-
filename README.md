# NexusMap v0.2.0

A custom, server-rendered map item -- no resource pack, no client mod.
One held item shows a top-down overview of your configured area, with
live player position blips and custom points of interest, using
Minecraft's real map iconography.

## What's polished in this pass
- **Real vanilla map icons, not colored squares.** Players show up as
  a rotating pointer arrow (yours is the classic "player" triangle,
  everyone else is a blue pointer) that actually turns to face the
  direction they're looking. POIs render as colored **banner icons**
  (red/blue/green/yellow/purple), matched to the color you pick with
  `/nexusmap addpoi`. These are native Minecraft map cursor types --
  the client renders them, so they look exactly like markers on a
  vanilla map, just placed by us.
- **Zoom levels you can cycle through.** Right-click the held map item
  and it swaps to the next zoom level in `map.zoom-levels` (default
  16 / 32 / 64 blocks-per-pixel, wraps around). Each zoom level has its
  own independently-cached terrain, so switching is instant -- no
  re-scanning on the spot.
- **Elevation shading.** Terrain color is now shaded lighter/darker
  based on how far above or below a sampled baseline height each pixel
  is, so hills and valleys actually read as relief instead of flat
  color blobs.
- **Much wider terrain palette.** Ores, concrete, terracotta, wool,
  crops, coral, ice, granite/diorite/andesite, nether materials, and
  more are now distinguished, instead of the handful of broad
  categories from the first pass.

## How it actually works
Minecraft's Map item is fully scriptable server-side via Bukkit's
`MapRenderer` and `MapCursorCollection` APIs -- we draw the terrain
pixels and place the icon cursors ourselves. Two layers per render:

1. **Terrain** -- a cached, periodically-refreshed color grid (one per
   zoom level) sampling the world's highest block at each pixel's world
   coordinate, elevation-shaded. This is the expensive part (see
   below), so it's cached and only redrawn when a refresh completes.
2. **Cursors** -- player pointers and POI banners, placed fresh every
   render call. This is cheap (just icon coordinates, no pixel work),
   so it stays live without needing its own refresh cycle.

## Commands
- `/nexusmap give` -- gives you the map item at the default zoom level
- `/nexusmap addpoi <name> [color]` (admin) -- marks your current
  location; color is one of red/blue/green/yellow/purple, defaults red
- `/nexusmap removepoi <name>` (admin)
- `/nexusmap listpoi`
- `/nexusmap refresh` (admin) -- forces a terrain refresh (all zoom
  levels) instead of waiting for the timer

## Config (`config.yml`)
- `map.world` -- which world this map covers
- `map.center-x` / `map.center-z` -- the map's center point (pick your
  spawn, or wherever your builds are concentrated)
- `map.zoom-levels` -- list of blocks-per-pixel scales, in cycle order.
  Default `[16, 32, 64]`. Smaller = more detail/less coverage.
- `map.default-zoom-index` -- which entry `/nexusmap give` hands out
  first (0-indexed into `zoom-levels`)
- `map.refresh-interval-minutes` -- how often terrain re-scans (default
  10 minutes), applied to every zoom level
- `map.pixels-per-tick` -- how many of each zoom level's 16,384 total
  pixels get scanned per server tick during a refresh

## The real limitation, read this before you judge the result
`getHighestBlockAt()` (what we use to sample terrain) will **load
unloaded chunks** if the area hasn't been visited before, which can
cause real lag or disk I/O during a refresh on a large, mostly-
unexplored map -- and now that's tripled, since three zoom levels
refresh independently. Two things to know:
- If your zoom levels' coverage areas include a lot of never-loaded
  terrain, the first refresh especially could be rough. Consider
  running the first `/nexusmap refresh` during low-population hours, or
  narrowing `zoom-levels` to fewer/larger-scale entries until you've
  playtested the load.
- This also means the terrain layer is only ever "recent," not live --
  it updates on the timer, not the moment someone places a block. Cursor
  icons (players, POIs) ARE fully live every render call -- only the
  colored terrain underneath is cached.

If refreshing three zoom levels at once turns out to be too much, the
easy fix is fewer zoom levels, a longer refresh interval, or staggering
each zoom level's refresh on a different offset instead of all three
starting simultaneously -- tell me what you see when you test it.

## Also worth knowing
- **Terrain colors are still approximate**, not vanilla map-accurate --
  categorized by material name pattern rather than matching Minecraft's
  real per-block map palette. Much wider coverage now, but still not
  pixel-perfect; easy to refine specific materials in
  `TerrainCache.colorFor()` once you see what looks off.
- **Single shared map set for everyone** -- all players see the same
  MapViews/items per zoom level; it's not per-player customized beyond
  the "which arrow is you" highlight. Per-player custom markers (like
  personal waypoints) would be a real extension, not what's built here.
- **Cursor direction rotation** uses the player's yaw at render time --
  should read as "facing" fairly accurately, but I haven't watched it
  in motion in-game, so flag it if the arrow rotation looks off.

## Setup (same flow as the others)
1. New repo, upload this folder's contents.
2. Codespace, `sdk use java 21.0.11-amzn` if needed.
3. `mvn clean package`
4. Jar into `plugins/`, restart.
5. Set your real `map.world`/`center-x`/`center-z` in `config.yml`
   before first launch if the defaults (world "world", 0,0) aren't
   right for your server.
6. `/nexusmap give`, then `/nexusmap refresh` to kick off the first
   terrain scan for all zoom levels. Right-click the item to try
   cycling zoom once terrain's drawn in.

Genuine polish pass, still a first real test in-game -- try it, see how
the icons/zoom/relief actually look and whether the triple-refresh
causes any lag, and send me what you find.

