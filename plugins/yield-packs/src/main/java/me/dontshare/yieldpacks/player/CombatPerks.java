package me.dontshare.yieldpacks.player;

import org.bukkit.entity.Player;

/**
 * The bought combat perks, in one place so every check agrees.
 * <p>
 * Pets fight on their own for everyone now, as in Pet Simulator 99 - that
 * used to be the paid "Auto Send" perk. What is sold instead is what PS99
 * sells: Auto Tap, which taps your pets' cube for you, and Premium, which
 * adds faster re-engaging after each kill.
 */
public final class CombatPerks {

    public static final String AUTO_TAP_PERMISSION = "yieldpacks.autotap";
    /**
     * What the old Free Auto Send pass granted. Anyone holding it bought a
     * combat perk that no longer exists on its own, so it counts as Auto
     * Tap rather than as nothing.
     */
    public static final String LEGACY_AUTO_SEND_PERMISSION = "yieldpacks.automode";
    /** Premium: pets re-engage 2x faster after each kill, and Auto Tap is included. */
    public static final String PREMIUM_PERMISSION = "yieldpacks.automode.premium";

    private CombatPerks() {
    }

    public static boolean hasAutoTap(Player player) {
        return player.hasPermission(AUTO_TAP_PERMISSION)
                || player.hasPermission(LEGACY_AUTO_SEND_PERMISSION)
                || player.hasPermission(PREMIUM_PERMISSION);
    }

    public static boolean hasPremium(Player player) {
        return player.hasPermission(PREMIUM_PERMISSION);
    }
}
