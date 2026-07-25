package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public class POIManager {

    private final NexusMapPlugin plugin;
    private final List<PointOfInterest> pois = new ArrayList<>();

    public POIManager(NexusMapPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void add(String name, Location location, Color color) {
        pois.removeIf(p -> p.getName().equalsIgnoreCase(name));
        pois.add(PointOfInterest.fromLocation(name, location, color));
        save();
    }

    public boolean remove(String name) {
        boolean removed = pois.removeIf(p -> p.getName().equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    public List<PointOfInterest> all() {
        return pois;
    }

    private void load() {
        pois.clear();
        for (String raw : plugin.getConfig().getStringList("pois")) {
            try {
                pois.add(PointOfInterest.deserialize(raw));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipped malformed POI entry: " + raw);
            }
        }
    }

    private void save() {
        List<String> serialized = pois.stream().map(PointOfInterest::serialize).toList();
        plugin.getConfig().set("pois", serialized);
        plugin.saveConfig();
    }
}
