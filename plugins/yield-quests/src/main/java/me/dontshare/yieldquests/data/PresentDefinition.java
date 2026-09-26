package me.dontshare.yieldquests.data;

import org.bukkit.Material;

/**
 * One present in the /daily chain - see PresentsContentLoader/daily.yml.
 * {@code rarity} is its display name ("Common"), {@code headTexture} the
 * present head it shows as (null: {@code headDatabaseId}, then
 * {@code fallbackMaterial}).
 */
public record PresentDefinition(long unlockAfterMinutes, String headDatabaseId, Material fallbackMaterial,
                                 long coins, long diamonds, String rarity, String headTexture) {
}
