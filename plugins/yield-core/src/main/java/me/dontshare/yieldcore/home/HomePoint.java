package me.dontshare.yieldcore.home;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A saved home, stored as plain primitives rather than a raw {@link Location} -
 * a {@code World} reference isn't cleanly BSON-serializable, so the world's
 * name is stored instead and re-resolved via {@link Bukkit#getWorld} on
 * every use. Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class HomePoint {

    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public HomePoint() {
    }

    public HomePoint(Location location) {
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }

    /** Null if the referenced world isn't currently loaded. */
    public Location toLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld, x, y, z, yaw, pitch);
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }
}
