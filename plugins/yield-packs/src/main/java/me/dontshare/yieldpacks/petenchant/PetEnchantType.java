package me.dontshare.yieldpacks.petenchant;

/**
 * The 5 "Common" per-pet enchant stats - matches {@code
 * me.dontshare.yieldpacks.enchant.EnchantType} in spirit (same 5 stats
 * this codebase already tracks everywhere) but is intentionally a
 * separate enum: player Enchant Books and pet Enchanting Table results
 * are unrelated mechanics (drag-and-drop slots vs. a replaced roll) that
 * only happen to share vocabulary.
 */
public enum PetEnchantType {
    COINS,
    GEMS,
    DAMAGE,
    ATTACK_SPEED,
    LUCK;

    public String displayName() {
        return switch (this) {
            case COINS -> "Coins";
            case GEMS -> "Gems";
            case DAMAGE -> "Damage";
            case ATTACK_SPEED -> "Attack Speed";
            case LUCK -> "Luck";
        };
    }
}
