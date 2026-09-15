package me.dontshare.yieldpacks.mastery;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** "/masteries" - a simple readout of all 4 passive XP tracks (see MasteryService), same "info-only" GUI shape as PotionsGui. */
public final class MasteryGui {

    private static final int TOTAL_ROWS = 3;
    private static final int CLOSE_SLOT = 22;
    /** Centered 4-across in the middle row - matches {@link MasteryType#values()}' own declared order (PACKS, ENCHANTS, MINING, COMBAT). */
    private static final int[] TRACK_SLOTS = {11, 12, 14, 15};

    private final PlayerDataStore<PackPlayerProfile> store;
    private final MasteryService service;
    private final GuiManager guiManager;

    public MasteryGui(PlayerDataStore<PackPlayerProfile> store, MasteryService service, GuiManager guiManager) {
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        var builder = Gui.builder(TOTAL_ROWS, "Masteries");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        MasteryType[] types = MasteryType.values();
        for (int i = 0; i < types.length && i < TRACK_SLOTS.length; i++) {
            builder.item(TRACK_SLOTS[i], buildTrackIcon(profile, types[i]));
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack buildTrackIcon(PackPlayerProfile profile, MasteryType type) {
        int level = service.levelOf(profile, type);
        long into = service.xpIntoCurrentLevel(profile, type);
        long needed = service.xpForLevel(level);
        double bonusPercent = service.bonusFor(profile, type) * 100;

        ItemBuilder builder = ItemBuilder.of(iconFor(type))
                .name(MenuLore.infoName(MenuLore.ACCENT, prettyName(type) + " Mastery"));
        MenuLore.info(
                "mastery",
                List.of(" &7" + descriptionFor(type)),
                MenuLore.ACCENT,
                List.of(
                        "Level: &f" + level,
                        "Progress: &f" + Formatting.format(into) + " &7/ &f" + Formatting.format(needed) + " &7xp",
                        "Bonus: &a+" + String.format(Locale.ROOT, "%.2f", bonusPercent) + "% " + bonusLabel(type)
                )
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private Material iconFor(MasteryType type) {
        return switch (type) {
            case PACKS -> Material.CHEST;
            case ENCHANTS -> Material.ENCHANTED_BOOK;
            case MINING -> Material.DIAMOND_PICKAXE;
            case COMBAT -> Material.DIAMOND_SWORD;
        };
    }

    private String prettyName(MasteryType type) {
        return switch (type) {
            case PACKS -> "Packs";
            case ENCHANTS -> "Enchants";
            case MINING -> "Mining";
            case COMBAT -> "Combat";
        };
    }

    private String descriptionFor(MasteryType type) {
        return switch (type) {
            case PACKS -> "Earned by opening packs.";
            case ENCHANTS -> "Earned by slotting Enchant Books.";
            case MINING -> "Earned by mining ore.";
            case COMBAT -> "Earned by destroying ore cubes.";
        };
    }

    /** Matches exactly which multiplier this track feeds in {@code YieldPacks#onEnable} - PACKS is additive Luck, the rest are multiplicative. */
    private String bonusLabel(MasteryType type) {
        return switch (type) {
            case PACKS -> "Luck";
            case ENCHANTS -> "Diamonds";
            case MINING -> "Coins";
            case COMBAT -> "Damage";
        };
    }
}
