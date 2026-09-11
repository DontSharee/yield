package me.dontshare.yieldzones.data;

import org.bukkit.Material;

/** One tier of ore cube a zone can roll - how tough it is, what it pays out (coins and XP), and how often it shows up. */
public record CubeTier(Material material, int maxHp, long coinValue, long xpValue, double weight) {
}
