package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** The Index/Collection screen - per-pack completion progress and the aggregate luck bonus it grants. */
public final class IndexGui {

    private static final String ACCENT = "<#4BD9FF>";

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final LuckService luckService;
    private final GuiManager guiManager;
    private final PackShopGui packShopGui;
    /** Set after construction - the two screens point at each other, so one of them has to be wired second. */
    private HugeIndexGui hugeIndexGui;

    public IndexGui(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                     LuckService luckService, GuiManager guiManager, PackShopGui packShopGui) {
        this.content = content;
        this.store = store;
        this.luckService = luckService;
        this.guiManager = guiManager;
        this.packShopGui = packShopGui;
    }

    public void setHugeIndexGui(HugeIndexGui hugeIndexGui) {
        this.hugeIndexGui = hugeIndexGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<PackDefinition> packs = content.get().packs().all();
        int contentRows = Math.max(1, Math.min(5, (packs.size() + 8) / 9));
        int totalRows = contentRows + 1;
        double luckPercent = (luckService.totalLuckMultiplier(profile) - 1.0) * 100;

        var builder = Gui.builder(totalRows, "Index (+" + Math.round(luckPercent) + "% luck)");
        int slot = 0;
        for (PackDefinition pack : packs) {
            if (slot >= contentRows * 9) {
                break;
            }
            builder.item(slot, buildIcon(pack, luckService.collectedFromPool(profile, pack)),
                    (clicker, event) -> packShopGui.open(clicker));
            slot++;
        }
        builder.fill(IntStream.range(contentRows * 9, totalRows * 9), GuiIcons.filler());
        builder.item(totalRows * 9 - 5, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        // Huges are never in a pack's pool, so they cannot appear on this
        // screen at all - this is the only way in to seeing them.
        if (hugeIndexGui != null) {
            builder.item(totalRows * 9 - 7, hugeIndexButton(), (clicker, event) -> hugeIndexGui.open(clicker));
        }
        guiManager.open(player, builder.build());
    }

    private ItemStack hugeIndexButton() {
        ItemBuilder builder = ItemBuilder.of(org.bukkit.Material.NETHER_STAR)
                .name(MenuLore.buttonName("<#FFD700>", "HUGE INDEX"));
        MenuLore.button("huge index",
                List.of(" &7Every &6Huge&7 in the game,", " &7and which ones you've found."),
                "<#FFD700>", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildIcon(PackDefinition pack, int collectedCount) {
        int total = pack.pool().size();
        ItemBuilder builder = ItemBuilder.of(pack.material())
                .name(pack.displayName() + " &7[" + Formatting.fancyFont("click") + "]");
        if (pack.customModelData() != null) {
            builder.modelData(pack.customModelData());
        }
        MenuLore.button(
                "collection",
                List.of(" &7Track which pets", " &7you've collected", " &7from &fthis pack&7!"),
                ACCENT,
                "Click to Open Pack"
        ).forEach(builder::lore);
        return builder
                .lore("")
                .lore("&7Collected: &f" + collectedCount + "/" + total)
                .lore("&aCollect them all&7 for &a+5% &7Luck!")
                .hideAttributes()
                .build();
    }
}
