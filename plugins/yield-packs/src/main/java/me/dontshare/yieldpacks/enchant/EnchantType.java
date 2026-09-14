package me.dontshare.yieldpacks.enchant;

import org.bukkit.Material;

/** The 5 enchant types - one per existing player-wide multiplier stat, matching YieldPacks'/LuckService's own registries exactly. */
public enum EnchantType {
    COINS("Coins", Material.SUNFLOWER),
    DIAMONDS("Diamonds", Material.DIAMOND),
    DAMAGE("Damage", Material.IRON_SWORD),
    ATTACK_SPEED("Attack Speed", Material.FEATHER),
    LUCK("Luck", Material.RABBIT_FOOT);

    private final String displayName;
    private final Material icon;

    EnchantType(String displayName, Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }
}
