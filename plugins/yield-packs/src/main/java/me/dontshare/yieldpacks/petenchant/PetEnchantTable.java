package me.dontshare.yieldpacks.petenchant;

import org.bukkit.Location;

/** One clickable Enchanting Table button - same 4-packet-entity shape (hitbox/button/wall/text) as yield-zonemachines' own {@code ZoneMachine}, minted fresh entity ids each content load. */
public record PetEnchantTable(Location location, int hitboxEntityId, int buttonEntityId, int wallEntityId, int textEntityId) {
}
