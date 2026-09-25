package me.dontshare.yieldquests.gui;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.PresentsService;
import me.dontshare.yieldquests.data.PresentDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 10 presents in a 2x5 grid, each a HeadDatabase head (or its configured
 * fallback material) - see {@link PresentsService}'s own javadoc for the
 * per-session unlock/claim rules this renders.
 */
public final class PresentsGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int INFO_SLOT = 4;
    /** Up to fourteen, seven to a row, each row centred. */
    private static final int MAX_PRESENTS = 14;
    private static final int CLOSE_SLOT = 31;

    private final PresentsService presentsService;
    private final me.dontshare.yieldquests.GiftDisplayService giftDisplay;
    private final GuiManager guiManager;
    private final HeadDatabaseAPI headDatabaseApi;

    public PresentsGui(PresentsService presentsService, me.dontshare.yieldquests.GiftDisplayService giftDisplay,
                       GuiManager guiManager) {
        this.presentsService = presentsService;
        this.giftDisplay = giftDisplay;
        this.guiManager = guiManager;
        this.headDatabaseApi = Bukkit.getPluginManager().getPlugin("HeadDatabase") != null ? new HeadDatabaseAPI() : null;
    }

    public void open(Player player) {
        GuiBuilder builder = Gui.builder(4, "Daily Presents")
                .fill(IntStream.range(0, 36), GuiIcons.filler())
                .item(INFO_SLOT, buildInfoIcon())
                .item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        List<PresentDefinition> presents = presentsService.presents();
        int shown = Math.min(presents.size(), MAX_PRESENTS);
        int[] slots = me.dontshare.yieldcore.gui.GuiLayout.centered(1, shown);
        for (int i = 0; i < shown; i++) {
            int index = i;
            builder.item(slots[i], buildPresentIcon(player, presents.get(index), index),
                    (clicker, event) -> attemptClaim(clicker, index));
        }

        guiManager.open(player, builder.build());
    }

    /**
     * Claiming from the menu doesn't pay into the balance directly any more:
     * the menu closes and the present drops in front of the player and
     * bursts open there, spraying its loot - the same moment as smacking one
     * that fell on its own (see GiftDisplayService).
     */
    private void attemptClaim(Player player, int index) {
        if (presentsService.isClaimed(player, index)) {
            player.sendMessage(Text.parse("<gray>Already claimed this session.</gray>"));
            return;
        }
        if (!presentsService.isUnlocked(player, index)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            player.sendMessage(Text.parse("<red>Not unlocked yet - keep playing!</red>"));
            return;
        }
        player.closeInventory();
        giftDisplay.openFromMenu(player, index);
    }

    private ItemStack buildInfoIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.KNOWLEDGE_BOOK).name(MenuLore.infoName(ACCENT, "HOW THIS WORKS"));
        List<String> description = List.of(
                "&7Presents unlock the longer you stay",
                "&7online THIS session - open them any time,",
                "&7in any order, no rush.");
        MenuLore.info("info", description, ACCENT, List.of("&cLogging out re-locks everything.")).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildPresentIcon(Player player, PresentDefinition present, int index) {
        boolean claimed = presentsService.isClaimed(player, index);
        boolean unlocked = presentsService.isUnlocked(player, index);

        ItemBuilder builder = ItemBuilder.of(resolveIcon(present));
        List<String> data = List.of(
                "&7Unlocks at: &f" + present.unlockAfterMinutes() + "m online",
                "&7Reward: &a$" + Formatting.format(present.coins())
                        + (present.diamonds() > 0 ? " &7+ &b" + present.diamonds() + " diamond(s)" : "")
        );

        if (claimed) {
            builder.name(MenuLore.name("&a", "Present") + " &7[Claimed]");
            MenuLore.info("present", List.of(), ACCENT, data).forEach(builder::lore);
        } else if (unlocked) {
            builder.name(MenuLore.buttonName(ACCENT, "PRESENT"));
            MenuLore.button("present", data, ACCENT, "Click to Claim").forEach(builder::lore);
        } else {
            builder.name(MenuLore.name("&7", "Locked Present"));
            List<String> lockedData = new java.util.ArrayList<>(data);
            lockedData.add("&7Unlocks in: &c" + presentsService.minutesUntilUnlock(player, index) + "m");
            MenuLore.info("present", List.of(), "&7", lockedData).forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    private ItemStack resolveIcon(PresentDefinition present) {
        if (present.headDatabaseId() != null && headDatabaseApi != null) {
            ItemStack head = headDatabaseApi.getItemHead(present.headDatabaseId());
            if (head != null) {
                return head;
            }
        }
        return new ItemStack(present.fallbackMaterial());
    }
}
