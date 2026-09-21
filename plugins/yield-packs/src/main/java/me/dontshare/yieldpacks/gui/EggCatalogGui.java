package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Every egg in the game, what comes out of it, and where to hatch it.
 * <p>
 * Read-only on purpose: nothing here hatches anything. Eggs are hatched at
 * their own station now (see yield-packstations), so what a player needs
 * away from one is not a button but an answer - which egg drops the pet
 * they are chasing, what it costs, and which station to walk to. This
 * replaced the old Pack Storage screen, which existed to manage a
 * stockpile that no longer exists.
 */
public final class EggCatalogGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int CONTENT_SLOTS = 45;
    private static final int PREV_SLOT = 45;
    private static final int HEADER_SLOT = 49;
    private static final int CLOSE_SLOT = 48;
    private static final int NEXT_SLOT = 53;

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final GuiManager guiManager;
    private final PackOddsLore oddsLore;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public EggCatalogGui(Supplier<PackContentLoader.ContentSnapshot> content, GuiManager guiManager,
                          PackOddsLore oddsLore) {
        this.content = content;
        this.guiManager = guiManager;
        this.oddsLore = oddsLore;
    }

    public void open(Player player) {
        List<PackDefinition> eggs = allEggs();
        Page<PackDefinition> page = Page.of(eggs, pageIndex.getOrDefault(player.getUniqueId(), 0), CONTENT_SLOTS);

        GuiBuilder builder = Gui.builder(6, "Eggs");
        int slot = 0;
        for (PackDefinition egg : page.items()) {
            builder.item(slot++, buildIcon(egg, player));
        }
        builder.fill(IntStream.range(slot, CONTENT_SLOTS), GuiIcons.filler());
        builder.fill(IntStream.range(CONTENT_SLOTS, 54)
                .filter(s -> s != PREV_SLOT && s != HEADER_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        builder.item(HEADER_SLOT, buildHeaderIcon(eggs.size()));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));
        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        int next = Math.max(0, pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        pageIndex.put(player.getUniqueId(), next);
        open(player);
    }

    /** Cheapest first, which is also roughly the order a player meets them. */
    private List<PackDefinition> allEggs() {
        List<PackDefinition> eggs = new ArrayList<>(content.get().packs().all());
        eggs.sort(Comparator.comparingLong(PackDefinition::coinCost).thenComparingInt(PackDefinition::sortOrder));
        return eggs;
    }

    private ItemStack buildIcon(PackDefinition egg, Player viewer) {
        ItemBuilder builder = ItemBuilder.of(Material.DRAGON_EGG).name(MenuLore.buttonName(ACCENT, egg.displayName()));
        List<String> lore = new ArrayList<>();
        lore.add("&8" + Formatting.fancyFont(whereToHatch(egg)));
        lore.add("");
        lore.addAll(oddsLore.lines(egg, viewer));
        lore.add("");
        lore.add("&7Cost: " + PackOddsLore.costLine(egg, 1));
        lore.forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * Where this egg physically is. Derived from the id rather than from a
     * zone lookup because yield-packs deliberately knows nothing about
     * yield-zones - the id convention ({@code zone_<zone>_pack},
     * {@code black_market_egg}) is the only coupling either side has, and
     * it is one the pack stations file already relies on.
     */
    private String whereToHatch(PackDefinition egg) {
        String id = egg.id();
        if (id.startsWith("zone_") && id.endsWith("_pack")) {
            return prettify(id.substring("zone_".length(), id.length() - "_pack".length())) + " station";
        }
        if (id.startsWith("black_")) {
            return "black market";
        }
        return egg.shopWeight() > 0 ? "merchant" : "special";
    }

    private String prettify(String snakeCase) {
        String[] words = snakeCase.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private ItemStack buildHeaderIcon(int total) {
        ItemBuilder builder = ItemBuilder.of(Material.BOOK).name(MenuLore.buttonName(ACCENT, "Eggs"));
        MenuLore.info("eggs", List.of(
                " &7Every egg in the game, what",
                " &7hatches from it, and where",
                " &7to go to hatch it."), ACCENT,
                List.of("&7Total: &f" + total)).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
