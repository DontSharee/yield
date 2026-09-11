package me.dontshare.yieldcosmetics.data;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * One cosmetic. {@code style} is the MiniMessage payload applied for this
 * cosmetic - an open color/gradient tag for chat colors and nameplates, or
 * literal badge text+color for tags. {@code permission} is what OWNS it;
 * null means free for everyone. There is no in-game purchase flow -
 * ownership is exactly having the permission, granted by a future
 * store/voucher system.
 */
public record Cosmetic(String id, CosmeticCategory category, String displayName, String permission,
                        String style, Material icon) {

    public boolean isOwnedBy(Player player) {
        return permission == null || player.hasPermission(permission);
    }
}
