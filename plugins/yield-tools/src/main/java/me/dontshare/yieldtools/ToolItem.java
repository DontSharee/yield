package me.dontshare.yieldtools;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldtools.data.ToolDefinition;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The tool as an item. It is only ever a marker: what a tool DOES comes
 * from the holder's own owned tool (see ToolService#tapMultiplier), never
 * from the item - so a copy picked up by someone else, or one left over
 * after an upgrade, is worth exactly nothing extra and there is nothing to
 * dupe.
 */
public final class ToolItem {

    private final NamespacedKey key;

    public ToolItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "tool");
    }

    public ItemStack create(ToolDefinition tool) {
        // Always the player's CURRENT weapon, so it always glows - the same
        // mark it wears in the menu.
        ItemBuilder builder = ItemBuilder.of(tool.material())
                .name(nameOf(tool))
                .tag(key, PersistentDataType.STRING, tool.id())
                .unbreakable(true)
                .enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        MenuLore.item("player weapon", List.of(
                "Damage: &a" + Formatting.format(tool.power()) + "x &7pet power",
                "Tier: &f" + Formatting.toRoman(tool.index() + 1)
        ), "&8Right Click to Upgrade").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** {@code Meadow Dagger [III]} - the weapon's name with its place on the path, as everywhere a weapon is named. */
    public static String nameOf(ToolDefinition tool) {
        return tool.displayName() + " &7[" + Formatting.toRoman(tool.index() + 1) + "]";
    }

    public boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }
}
