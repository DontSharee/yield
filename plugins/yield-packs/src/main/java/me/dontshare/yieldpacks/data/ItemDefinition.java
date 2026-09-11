package me.dontshare.yieldpacks.data;

import me.dontshare.yieldpacks.fusion.FusionTier;
import org.bukkit.Material;

import java.util.List;

/**
 * A pet definition. Every base pet is entirely config-driven (see
 * packs.yml); its {@link FusionTier#GOLDEN}/{@code RAINBOW}/
 * {@code DARK_MATTER} variants are synthesized automatically at load time
 * from that same base entry (see {@code PackContentLoader.loadItems}) -
 * {@code baseItemId} is what ties all of one pet's tiers back together for
 * fusing, and never changes across them. If {@code headDatabaseId} is set
 * (and the HeadDatabase plugin is installed), the pet renders as that
 * HeadDatabase player head instead of {@code material} - see
 * {@code me.dontshare.yieldpacks.item.ItemIconFactory}.
 * <p>
 * Pets earn their keep by fighting ore cubes (see yield-zones'
 * {@code PetCombatController}), not by idle income - {@code damage} is how
 * much HP one hit takes off. Every pet attacks on the same shared cadence
 * (see {@code PetCombatController#ATTACK_INTERVAL_TICKS}) - damage is the
 * only thing that varies pet-to-pet.
 */
public record ItemDefinition(String id, String displayName, Material material, Integer customModelData,
                              String headDatabaseId, String rarityId, double damage,
                              boolean trackExists, List<String> lore, FusionTier fusionTier, String baseItemId,
                              boolean huge, double hugeDamagePercent) {
}
