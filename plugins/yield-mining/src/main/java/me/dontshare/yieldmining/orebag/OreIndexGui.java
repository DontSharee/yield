package me.dontshare.yieldmining.orebag;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldmining.data.MiningContent;
import me.dontshare.yieldmining.data.OreDefinition;
import me.dontshare.yieldmining.data.MiningProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * "/oreindex" - a read-only discovery catalog of the ore materials
 * mining.yml configures, showing which ones this player has ever pulled a
 * Special Ore of. No pagination - the configured ore roster is small
 * (8 today) and this GUI simply grows a row at a time as more are added,
 * same "content rows + one nav row" shape as StoreGui, capped at a normal
 * 6-row chest.
 */
public final class OreIndexGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int MAX_CONTENT_SLOTS = 45;

    private final Supplier<MiningContent> content;
    private final PlayerDataStore<MiningProfile> store;
    private final GuiManager guiManager;

    public OreIndexGui(Supplier<MiningContent> content, PlayerDataStore<MiningProfile> store, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        MiningProfile profile = store.getOrCreate(player.getUniqueId());
        List<OreDefinition> ores = new ArrayList<>(content.get().ores().values());
        ores.removeIf(definition -> definition.dropMaterial() == null);

        int contentSlots = Math.min(MAX_CONTENT_SLOTS, ores.size());
        int contentRows = Math.max(1, (contentSlots + 8) / 9);
        int totalRows = Math.min(6, contentRows + 1);
        int closeSlot = totalRows * 9 - 5;

        var builder = Gui.builder(totalRows, "Ore Index");
        for (int i = 0; i < ores.size() && i < contentRows * 9; i++) {
            OreDefinition definition = ores.get(i);
            boolean discovered = profile.getDiscoveredOreMaterials().contains(definition.dropMaterial().name());
            builder.item(i, discovered ? discoveredIcon(definition.dropMaterial()) : lockedIcon(definition.dropMaterial()));
        }
        builder.item(closeSlot, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private ItemStack discoveredIcon(Material dropMaterial) {
        ItemBuilder builder = ItemBuilder.of(dropMaterial).name("<gray>" + prettyName(dropMaterial) + "</gray> <dark_gray>[ORE]</dark_gray>");
        MenuLore.info("ore index", List.of(
                " &7Merge with other ores, to craft",
                " &7absurd items!"
        ), ACCENT, List.of("Use at &e/forge&7!")).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack lockedIcon(Material dropMaterial) {
        ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE).name("&7&l???");
        MenuLore.info("ore index", List.of(
                " &7Mine " + prettyName(dropMaterial) + " for a",
                " &7chance at Special Ore!"
        ), ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private String prettyName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }
}
