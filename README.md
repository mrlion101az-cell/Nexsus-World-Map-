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

## v0.2.1 -- the lag fix

Confirmed the lag: `render()` gets called by Bukkit once per viewer roughly every server tick
(20/sec) for as long as the map item is held, and touching the canvas at all -- even if nothing
actually changed -- makes the server send that viewer a full map update packet. The renderer was
redrawing the full 128x128 terrain layer and rescanning every online player for cursors on every
single one of those calls, regardless of whether the viewer had moved an inch. That's the real
cost, more than the pixel math itself: full packets going out 20 times a second per player holding
a map, whether they're standing still or not.

Two fixes, both in `WorldMapRenderer`:
- A per-viewer minimum real-time gap between actual redraws (`map.render-interval-ticks` in
  `config.yml`, default 4 -- at most 5 redraws/sec per viewer instead of 20). Below that gap,
  `render()` returns immediately without touching the canvas at all, so no packet goes out that
  tick.
- Independently, the terrain layer only gets rebuilt when the viewer's centre cell has actually
  changed since it was last drawn -- with `blocksPerPixel` usually 16-64, that's however many
  blocks of real walking before the terrain layer needs to change at all, so it skips the
  16,384-write terrain redraw on most throttled renders even when the throttle above still lets a
  cursor-only redraw through.

Also hoisted a `getConfig().getString("map.world", ...)` lookup out of the per-online-player cursor
loop in `drawCursors` -- it was re-reading that config value once per online player per render call
instead of once per call, a smaller but real redundant cost in the same hot path.

`NexusMapPlugin` also picked up the `copyDefaults(true)` + `saveConfig()` config-merge pattern on
enable, so `render-interval-ticks` (and anything config.yml gains later) actually reaches a server
that already has a config.yml on disk from before -- `saveDefaultConfig()` alone only ever writes
that file the very first time, never updates an existing one.

If lag is still noticeable with a lot of players on the map at once, raise
`map.render-interval-ticks` further (8 or higher trades responsiveness for less load); if you've
got room to spare and want the smoothest possible cursor movement, it can go as low as 1.

## v0.2.2 -- fix real build failure from v0.2.1

`mvn package` against the real Paper API failed on v0.2.1 with two errors, both from gaps in my
own local stub environment (the sandbox I compile-verify in doesn't have network access to the
real Paper API, so I maintain a hand-written approximation of it -- this is the second time this
approximation has been wrong in a way the person's own build caught and I didn't):

1. **`MapCanvas#setPixelColor` takes `java.awt.Color`, not `org.bukkit.Color`.** Every other
   colour-handling in this plugin (`TerrainCache`, `PointOfInterest`) correctly uses
   `org.bukkit.Color`, and reasonably so -- `setPixelColor` is really the odd one out here, a real
   Paper API quirk. Fixed at the one call site in `drawTerrain` by converting to `java.awt.Color`
   right before the `setPixelColor` call; nothing else in the plugin needed to change.
2. **`MapCursor.Type` constants were wrong.** I had `BLUE_POINTER`, `BLUE_BANNER`,
   `GREEN_BANNER`, `YELLOW_BANNER`, `PURPLE_BANNER`, `RED_BANNER` -- none of those exist in the
   current API. The real names (verified against Paper's own 1.21.x javadocs, not guessed again)
   are `BLUE_MARKER` for another player's pointer, and `BANNER_<COLOR>` (`BANNER_BLUE`,
   `BANNER_GREEN`, `BANNER_YELLOW`, `BANNER_PURPLE`, `BANNER_RED`) for POI banners -- the colour
   goes after "BANNER_", not before. `MapCursor.Type` has also become an interface backed by a
   registry in modern Paper rather than a plain enum, though that didn't require any code changes
   here beyond the naming fix, since nothing in this plugin iterates or switches over it.

Same note as always: this sandbox's stub-based compile check is necessary but not sufficient --
it only catches errors within its own (evidently still imperfect) model of the Bukkit/Paper API.
Appreciate you running the real build and sending back the actual errors again; both my local
stubs for `MapCanvas` and `MapCursor.Type` are corrected now, so future patches to this plugin (and
any other Nexus plugin using map cursors) should be checked against the right shapes going forward.

