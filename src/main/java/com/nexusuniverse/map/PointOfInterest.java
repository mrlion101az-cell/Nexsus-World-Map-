package com.nexusuniverse.map;

import org.bukkit.Color;
import org.bukkit.Location;

public class PointOfInterest {

    private final String name;
    private final int x;
    private final int z;
    private final Color color;

    public PointOfInterest(String name, int x, int z, Color color) {
        this.name = name;
        this.x = x;
        this.z = z;
        this.color = color;
    }

    public static PointOfInterest fromLocation(String name, Location location, Color color) {
        return new PointOfInterest(name, location.getBlockX(), location.getBlockZ(), color);
    }

    public String getName() {
        return name;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public Color getColor() {
        return color;
    }

    public String serialize() {
        return name + "," + x + "," + z + "," + color.asRGB();
    }

    public static PointOfInterest deserialize(String raw) {
        String[] parts = raw.split(",");
        return new PointOfInterest(
                parts[0],
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Color.fromRGB(Integer.parseInt(parts[3]))
        );
    }
}
