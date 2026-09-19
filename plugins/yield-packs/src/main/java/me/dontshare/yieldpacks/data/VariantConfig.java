package me.dontshare.yieldpacks.data;

import java.util.Map;

/**
 * The two orthogonal "this pull was special" rolls that sit on top of a
 * pack's normal rarity table - see packs.yml's own {@code huge:} and
 * {@code shiny:} sections.
 * <p>
 * They work in deliberately different ways, and the difference matters:
 * <ul>
 *   <li><b>Huge</b> replaces the pet you rolled with a Huge version of it.
 *       A Huge is its own {@link ItemDefinition}, synthesized per base pet
 *       exactly the way fusion tiers already are, because it is a
 *       genuinely different pet: it renders at {@code huge-scale-multiplier}
 *       times normal size, and its damage is derived from the owner's best
 *       normal pet rather than from a stat of its own (see {@code
 *       EquipmentService#effectiveDamage}), so a Huge pulled on day one is
 *       still carrying the squad in the last zone.</li>
 *   <li><b>Shiny</b> is a flag on the owned {@link me.dontshare.yieldpacks.pet.PetInstance},
 *       NOT a separate definition. It has to be, because it stacks with
 *       everything else: a pet can be Shiny and Golden and Huge at once,
 *       and synthesizing an ItemDefinition per combination would multiply
 *       the registry by four for no benefit.</li>
 * </ul>
 * Huge odds are multiplied by the player's luck; Shiny odds deliberately
 * are not - it is meant to be a flat, always-the-same surprise that luck
 * investment doesn't erode the novelty of.
 */
public record VariantConfig(double hugeChance, Map<String, Double> hugeDamagePercentByRarity,
                             double shinyChance, double shinyDamageMultiplier) {

    public static final VariantConfig DEFAULTS =
            new VariantConfig(0.00005, Map.of(), 0.025, 1.25);

    /** Whether a pet of this rarity has a Huge version at all - i.e. whether the rarity is listed in {@code huge.damage-percent}. */
    public boolean hasHugeVariant(String rarityId) {
        return hugeDamagePercentByRarity.containsKey(rarityId);
    }

    /** How much stronger this rarity's Huge is than the owner's best normal pet, e.g. 1.0 = double. Zero for a rarity with no Huge. */
    public double hugeDamagePercentFor(String rarityId) {
        return hugeDamagePercentByRarity.getOrDefault(rarityId, 0.0);
    }
}
