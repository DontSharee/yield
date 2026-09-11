package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.potion.PotionService;
import me.dontshare.yieldachievements.potion.PotionStat;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** /potions - a simple readout of every currently-active potion effect and its remaining time. */
public final class PotionsGui {

    private static final int TOTAL_ROWS = 4;
    private static final int CLOSE_SLOT = 31;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final PotionService potionService;
    private final GuiManager guiManager;

    public PotionsGui(PlayerDataStore<PackPlayerProfile> store, PotionService potionService, GuiManager guiManager) {
        this.store = store;
        this.potionService = potionService;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<PotionService.ActivePotion> active = potionService.activePotions(profile);

        var builder = Gui.builder(TOTAL_ROWS, "Active Potions");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        if (active.isEmpty()) {
            builder.item(13, buildEmptyIcon());
        } else {
            int slot = 10;
            for (PotionService.ActivePotion potion : active) {
                if (slot >= 17 && slot < 19) {
                    slot = 19;
                }
                if (slot >= 26) {
                    break;
                }
                builder.item(slot, buildPotionIcon(potion));
                slot++;
            }
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack buildEmptyIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.GLASS_BOTTLE).name(MenuLore.infoName("&7", "No Active Potions"));
        MenuLore.info("status", List.of(), MenuLore.ACCENT, List.of()).forEach(builder::lore);
        builder.lore("&7Drink a potion to see it here.");
        return builder.hideAttributes().build();
    }

    private ItemStack buildPotionIcon(PotionService.ActivePotion potion) {
        String statLabel = capitalize(potion.stat().name().replace('_', ' ').toLowerCase(Locale.ROOT));
        String statColor = statColor(potion.stat());
        String multiplierLabel = potion.multiplier() == Math.rint(potion.multiplier())
                ? String.valueOf((long) potion.multiplier())
                : String.valueOf(potion.multiplier());
        ItemBuilder builder = ItemBuilder.of(Material.POTION)
                .name(MenuLore.infoName(MenuLore.ACCENT, "x" + multiplierLabel + " ") + statColor + statLabel);
        MenuLore.info("potion", List.of(), MenuLore.ACCENT,
                List.of("Time Left: &f" + formatDuration(potion.remainingSeconds()))).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** Thematic color per stat, matching the resource-word coloring used across every other GUI's lore. */
    private String statColor(PotionStat stat) {
        return switch (stat) {
            case COINS -> "&6";
            case DAMAGE -> "&c";
            case LUCK -> "&a";
            case ROLL_SPEED -> "&b";
        };
    }

    private String capitalize(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return result.toString().trim();
    }

    private String formatDuration(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
