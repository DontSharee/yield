package me.dontshare.yieldmining.enchant;

import org.bukkit.Material;

import java.util.List;

/**
 * One pickaxe enchant, matching the reference config shape 1:1 (see
 * pickaxe-enchants.yml): a display icon/name/colors, which mining reward
 * channel it boosts, a max level, a rebirth gate, and three
 * {@link me.dontshare.yieldcore.math.FormulaEvaluator}-evaluated curves in
 * terms of "level" - cost per next level, the boost magnitude once bought,
 * and the percent chance (0-100) it actually procs on a given mine.
 * <p>
 * {@code mastery}, once every level is bought, unlocks up to 5 extra
 * levels bought with gems that each multiply the boost by 1.6x and add
 * +10% proc chance - see {@link PickaxeEnchantService#masterUp}.
 */
public record PickaxeEnchantDefinition(
        String id,
        int numericId,
        String colorPrimary,
        String colorSecondary,
        String displayName,
        Material displayItem,
        List<String> description,
        MiningRewardType type,
        int maxLevel,
        int rebirthRequirement,
        CostCurrency costCurrency,
        String costFormula,
        String boostFormula,
        String chanceFormula,
        boolean mastery
) {
}
