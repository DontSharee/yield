package me.dontshare.yieldachievements.store;

import org.bukkit.Material;

import java.math.BigInteger;
import java.util.List;

/**
 * A rank or gamepass purchasable with Credits. "commands" run as CONSOLE on
 * purchase, each with "%player%" substituted for the buyer's name - e.g.
 * {@code "lp user %player% parent add vip"} for a rank, or any arbitrary
 * command sequence for a gamepass effect. Deliberately not tied to any one
 * permissions plugin's API - console commands work with whatever's actually
 * installed (LuckPerms here), and cover gamepasses that need more than a
 * single permission grant.
 */
public record StoreProduct(String id, String displayName, List<String> description, Material icon,
                            BigInteger cost, List<String> commands, StoreProductCategory category) {
}
