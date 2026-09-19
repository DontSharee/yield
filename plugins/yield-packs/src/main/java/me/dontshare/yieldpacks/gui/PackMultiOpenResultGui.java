package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.roll.PackRollService.RollResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The PS99-style "egg grid" reveal for a multi-open (see {@code
 * PackOpenService#tryOpenMany}) - a full-screen, read-only grid of every
 * item rolled at once, instead of the single-item Title {@code
 * RollAnimationService} shows for a normal 1-pack open. Purely a display:
 * every roll has already been applied (pets granted, XP/exists-counter/
 * events already fired) by the time this opens.
 */
public final class PackMultiOpenResultGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 5;
    private static final int HEADER_SLOT = 4;
    /** Rows 1-3 (27 slots) - comfortably covers PackRollService#MULTI_OPEN_CAP (24). */
    private static final List<Integer> RESULT_SLOTS = IntStream.range(9, 36).boxed().toList();
    private static final int CLOSE_SLOT = 40;

    private final GuiManager guiManager;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final ItemIconFactory iconFactory;
    private PackStorageGui packStorageGui;

    public PackMultiOpenResultGui(GuiManager guiManager, Supplier<RarityRegistry> rarityRegistry, ItemIconFactory iconFactory) {
        this.guiManager = guiManager;
        this.rarityRegistry = rarityRegistry;
        this.iconFactory = iconFactory;
    }

    /** Breaks the constructor cycle with {@link PackStorageGui} - same setter-injection idiom {@code OpenPackDialog} already uses. */
    public void setPackStorageGui(PackStorageGui packStorageGui) {
        this.packStorageGui = packStorageGui;
    }

    public void open(Player player, List<RollResult> rolls) {
        var builder = Gui.builder(TOTAL_ROWS, "Opened " + rolls.size() + " Packs!");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9)
                .filter(slot -> slot != HEADER_SLOT && !RESULT_SLOTS.contains(slot) && slot != CLOSE_SLOT), GuiIcons.filler());

        builder.item(HEADER_SLOT, buildHeaderIcon(rolls));
        for (int i = 0; i < RESULT_SLOTS.size(); i++) {
            if (i < rolls.size()) {
                builder.item(RESULT_SLOTS.get(i), buildResultIcon(rolls.get(i)));
            }
        }
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> {
            if (packStorageGui != null) {
                packStorageGui.open(clicker);
            } else {
                clicker.closeInventory();
            }
        });

        guiManager.open(player, builder.build());
    }

    private ItemStack buildHeaderIcon(List<RollResult> rolls) {
        RollResult best = rolls.stream()
                .max(Comparator.comparingInt(r -> rarityOf(r.item()).sortOrder()))
                .orElse(null);
        ItemBuilder builder = ItemBuilder.of(Material.CHEST).name(ACCENT + "&lOpened " + rolls.size() + " Packs!");
        List<String> data = best == null ? List.of() : List.of(
                "&7Best: " + rarityColor(best.item()) + Formatting.stripLeadingColorCodes(best.item().displayName())
        );
        MenuLore.info("multiopen", data, ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildResultIcon(RollResult roll) {
        ItemDefinition item = roll.item();
        Rarity rarity = rarityOf(item);
        String color = "<" + rarity.colorHex() + ">";
        ItemBuilder builder = iconFactory.baseIcon(item)
                .name(color + Formatting.stripLeadingColorCodes(item.displayName()));
        List<String> data = roll.firstTimeCollected()
                ? List.of("&a&lNEW!")
                : List.of();
        MenuLore.info("multiopen-result", data, color, List.of(rarity.displayName())).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private Rarity rarityOf(ItemDefinition item) {
        return rarityRegistry.get().getOrThrow(item.rarityId());
    }

    private String rarityColor(ItemDefinition item) {
        return "<" + rarityOf(item).colorHex() + ">";
    }
}
