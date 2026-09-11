package me.dontshare.yieldpacks.leveling;

import org.bukkit.Material;

/** Feeding this to a specific pet (hold it, click the pet in the Bag) raises that pet's own level cap by {@code levelCapBonus}. {@code dropChance} is this pass's placeholder source - a luck-modified roll on every ore cube kill, easy to retarget at real player islands later without changing this data model. */
public record Candy(String id, String displayName, Material material, int levelCapBonus, double dropChance) {
}
