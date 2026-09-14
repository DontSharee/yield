package me.dontshare.yieldteams.data;

import org.bukkit.Material;

/** The trophy item's drop chance (per ore cube kill, luck-modified the same way candy/diamond drops already are) and appearance. */
public record TrophyConfig(double dropChance, Material material, String displayName) {
}
