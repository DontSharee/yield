package me.dontshare.yieldranks.data;

import org.bukkit.Material;

/**
 * One purchasable donor rank (e.g. "vip", "celestial") - the id matches the
 * one baked into the matching store.yml product's commands
 * ("admin ranks apply %player% &lt;id&gt;"). {@code sortOrder} is what lets
 * {@code DonorRankService#applyRank} refuse a downgrade (a higher-sortOrder
 * rank is strictly better, never re-appliable by a lower one).
 * <p>
 * {@code coinMultiplier}/{@code diamondMultiplier}/{@code xpMultiplier} feed
 * yield-packs'/yield-leveling's existing multiplier registries directly (a
 * bare 1.0 means "no effect" for a rank that doesn't grant that stat).
 * {@code luckBonus} is additive, matching yield-packs' LuckService's own
 * "1.0 + sum of extra providers" shape - a 0.0 means no luck bonus.
 */
public record DonorRank(
        String id,
        String displayName,
        int sortOrder,
        Material icon,
        double coinMultiplier,
        double diamondMultiplier,
        double xpMultiplier,
        double luckBonus,
        int bonusPetSlots,
        int bonusEnchantSlots,
        String chatColorCosmeticId,
        String tagCosmeticId
) {
}
