package me.dontshare.yieldlootboxes;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldlootboxes.data.LootboxDefinition;
import me.dontshare.yieldlootboxes.data.LootboxRewardEntry;
import me.dontshare.yieldpacks.data.ItemRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A real, giveable item sitting in the player's inventory - right-click to
 * open (see {@link LootboxConsumeListener}), same "tag with an id, no
 * pre-registration needed" idiom as yield-achievements' {@code PotionItem}.
 * Renders as whichever {@code Material} the box's own {@code icon:} says in
 * lootboxes.yml (an ender chest, a shulker box, whatever fits) - that config
 * value IS the real item, not just a GUI icon.
 */
public final class LootboxItem {

    private final NamespacedKey key;
    private final Supplier<ItemRegistry> itemRegistry;

    public LootboxItem(JavaPlugin plugin, Supplier<ItemRegistry> itemRegistry) {
        this.key = new NamespacedKey(plugin, "lootbox_id");
        this.itemRegistry = itemRegistry;
    }

    public ItemStack create(LootboxDefinition box) {
        return create(box, 1);
    }

    public ItemStack create(LootboxDefinition box, int amount) {
        List<String> lore = new ArrayList<>(MenuLore.info("lootbox", List.of(), MenuLore.ACCENT, buildOddsLines(box)));
        lore.add("");
        lore.add("&8Right-Click to Open");

        return ItemBuilder.of(box.icon())
                .name(box.displayName())
                .lore(lore)
                .tag(key, PersistentDataType.STRING, box.id())
                .amount(Math.max(1, Math.min(64, amount)))
                .hideAttributes()
                .build();
    }

    /** The box id this item was tagged with, or null if it isn't a lootbox item at all. */
    public String boxIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** "&lt;reward's own label&gt; &7(12.3%)" per pool entry, rarest last - same odds-disclosure idiom PackShopGui's own buildOddsLines already established for Packs. */
    private List<String> buildOddsLines(LootboxDefinition box) {
        double total = box.pool().stream().mapToDouble(LootboxRewardEntry::weight).sum();
        if (total <= 0) {
            return List.of();
        }
        ItemRegistry registry = itemRegistry.get();
        return box.pool().stream()
                .sorted(Comparator.comparingDouble(LootboxRewardEntry::weight).reversed())
                .map(entry -> rewardLabel(entry, registry) + " &7(" + formatPercent(entry.weight() / total * 100) + "%)")
                .toList();
    }

    private String rewardLabel(LootboxRewardEntry entry, ItemRegistry registry) {
        return switch (entry.type()) {
            case FLAT_COINS -> "&6+" + Formatting.format((double) entry.amount()) + " Coins";
            case FLAT_DIAMONDS -> "&b+" + Formatting.format((double) entry.amount()) + " Diamonds";
            case FLAT_CREDITS -> "&d+" + Formatting.format((double) entry.amount()) + " Credits";
            case PET -> registry.find(entry.petItemId()).map(item -> item.displayName()).orElse("&7Unknown Pet");
            case COMMANDS -> "&5&lBonus Reward";
        };
    }

    private String formatPercent(double percent) {
        return percent >= 10 ? String.valueOf(Math.round(percent)) : String.format(Locale.ROOT, "%.1f", percent);
    }
}
