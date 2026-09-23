package me.dontshare.yieldtools;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldtools.data.ToolDefinition;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

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
        ItemBuilder builder = ItemBuilder.of(tool.material())
                .name(tool.displayName())
                .tag(key, PersistentDataType.STRING, tool.id())
                .unbreakable(true)
                .hideAttributes();
        tool.description().forEach(builder::lore);
        builder.lore("");
        builder.lore("&7Taps: &ax" + Formatting.format(tool.tapMultiplier()) + " &7damage while held");
        builder.lore("&8Right-click to open /tools");
        return builder.build();
    }

    public boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }
}
