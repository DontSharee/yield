package me.dontshare.yieldquests.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiClickHandler;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.Heads;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldquests.GiftDisplayService;
import me.dontshare.yieldquests.PresentsService;
import me.dontshare.yieldquests.data.PresentDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * /daily - "Daily Gifts": the refresh clock at the top, and the day's gifts
 * below it, seven to a row and centred, each a present head stacked to its
 * number. Every countdown ticks down live while the menu is open.
 * <p>
 * The text is the reference design's, line for line - see {@link #giftIcon}.
 */
public final class PresentsGui {

    private static final String TITLE = "&nDaily Gifts";
    private static final int REFRESH_SLOT = 4;
    private static final int PER_ROW = 7;
    private static final int MAX_GIFTS = PER_ROW * 2;
    private static final String SUBTITLE = "&8" + Formatting.fancyFont("daily reward");

    private final JavaPlugin plugin;
    private final PresentsService presentsService;
    private final GiftDisplayService giftDisplay;
    private final GuiManager guiManager;
    private final YieldPacks packs;

    public PresentsGui(JavaPlugin plugin, PresentsService presentsService, GiftDisplayService giftDisplay,
                       GuiManager guiManager, YieldPacks packs) {
        this.plugin = plugin;
        this.presentsService = presentsService;
        this.giftDisplay = giftDisplay;
        this.guiManager = guiManager;
        this.packs = packs;
    }

    public void open(Player player) {
        List<PresentDefinition> presents = presentsService.presents();
        int shown = Math.min(presents.size(), MAX_GIFTS);
        int[] slots = slots(shown);
        Gui gui = Gui.builder(4, TITLE).plain().build();
        render(gui, player, presents, slots);
        guiManager.open(player, gui);

        // The countdowns tick while it's open; the task ends when it closes.
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != gui) {
                task[0].cancel();
                return;
            }
            render(gui, player, presentsService.presents(), slots);
        }, 20L, 20L);
    }

    private void render(Gui gui, Player player, List<PresentDefinition> presents, int[] slots) {
        gui.set(REFRESH_SLOT, refreshIcon(), null);
        for (int i = 0; i < slots.length && i < presents.size(); i++) {
            int index = i;
            GuiClickHandler click = (clicker, event) -> attemptClaim(clicker, index);
            gui.set(slots[i], giftIcon(player, presents.get(i), i), click);
        }
    }

    /** Seven to a row from the second row down, each row centred: twelve gifts sit in 10-16 and 20-24. */
    static int[] slots(int count) {
        int[] slots = new int[count];
        int first = Math.min(PER_ROW, count);
        int second = count - first;
        for (int i = 0; i < first; i++) {
            slots[i] = 9 + (9 - first) / 2 + i;
        }
        for (int i = 0; i < second; i++) {
            slots[first + i] = 18 + (9 - second) / 2 + i;
        }
        return slots;
    }

    /**
     * Opening from the menu closes it and drops the present in front of the
     * player, where it bursts and sprays its loot (see GiftDisplayService).
     */
    private void attemptClaim(Player player, int index) {
        if (presentsService.isClaimed(player, index) || !presentsService.isUnlocked(player, index)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        player.closeInventory();
        giftDisplay.openFromMenu(player, index);
    }

    private ItemStack refreshIcon() {
        return ItemBuilder.of(Material.CLOCK)
                .name("&6&lGifts &e&lRefresh")
                .lore("&7You'll be able to claim these gifts")
                .lore("&7again in &f" + countdown(presentsService.millisUntilReset()) + "&7..")
                .lore("&e&oMake sure to come back tomorrow!")
                .hideAttributes()
                .build();
    }

    private ItemStack giftIcon(Player player, PresentDefinition present, int index) {
        ItemBuilder builder = ItemBuilder.of(icon(present)).amount(index + 1);
        String rarity = present.rarity();
        if (presentsService.isClaimed(player, index)) {
            builder.name("&8&l" + rarity + " &7&lGift")
                    .lore(SUBTITLE)
                    .lore("")
                    .lore("&7&oYou already claimed this gift.")
                    .lore("&7&oIt returns in &f" + countdown(presentsService.millisUntilReset()) + ".")
                    .lore("")
                    .lore("&8&lCLAIMED");
        } else if (presentsService.isUnlocked(player, index)) {
            builder.name("&2&l" + rarity + " &a&lGift")
                    .lore(SUBTITLE)
                    .lore("")
                    .lore("&7&oThis gift is ready to claim.")
                    .lore("&7&oClick to open it!")
                    .lore("")
                    .lore("&a&lCLICK TO CLAIM");
        } else {
            builder.name("&4&l" + rarity + " &c&lGift")
                    .lore(SUBTITLE)
                    .lore("")
                    .lore("&7&oYou cannot claim this gift yet.")
                    .lore("&7&oUnlocks in &f" + countdown(presentsService.millisUntilUnlock(player, index)) + ".")
                    .lore("")
                    .lore("&c&lLOCKED");
        }
        return builder.hideAttributes().build();
    }

    /** The rarity's present head, else the present's own HeadDatabase head or material. */
    public static ItemStack icon(PresentDefinition present, YieldPacks packs) {
        if (present.headTexture() != null) {
            return Heads.texture(present.headTexture());
        }
        return packs.getIconFactory().headOrFallback(present.headDatabaseId(), present.fallbackMaterial());
    }

    private ItemStack icon(PresentDefinition present) {
        return icon(present, packs);
    }

    /** {@code 2h 47m 8s}, {@code 4m 23s}, {@code 55s}. */
    static String countdown(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + secs + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }
}
