package me.dontshare.yieldpacks.petenchant;

import java.util.Map;

/**
 * One "Unique" enchant - unlike a Common, has no I-V levels, is picked
 * uniformly among every other Unique when the roll lands Unique at all
 * (see {@link PetEnchantService#rollFor}), and can bundle bonuses across
 * multiple {@link PetEnchantType}s at once (e.g. Royalty). {@code
 * specialEffect} is null for a pure-stat Unique; a non-null id (e.g.
 * {@code "BONUS_GEM_DROP"}) is checked by name at the one call site that
 * needs real behavior beyond a flat stat (see {@link
 * PetEnchantService#hasBonusGemDropEnchant}).
 */
public record PetUniqueDefinition(String id, String displayName, String colorHex,
                                   Map<PetEnchantType, Double> statBonuses, String specialEffect) {
}
