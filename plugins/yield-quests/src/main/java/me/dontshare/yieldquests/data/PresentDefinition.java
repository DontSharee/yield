package me.dontshare.yieldquests.data;

import org.bukkit.Material;

/** One present in the /daily chain - see PresentsContentLoader/daily.yml. */
public record PresentDefinition(long unlockAfterMinutes, String headDatabaseId, Material fallbackMaterial,
                                 long coins, long diamonds) {
}
