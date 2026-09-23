package me.dontshare.yieldcosmetics.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcosmetics.CosmeticPopularityService;
import me.dontshare.yieldcosmetics.CosmeticService;
import me.dontshare.yieldcosmetics.data.Cosmetic;
import me.dontshare.yieldcosmetics.data.CosmeticCategory;
import me.dontshare.yieldcosmetics.data.CosmeticProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Category tabs (Chat Colors / Nameplates / Tags) across the top row, a
 * paginated grid of that category's cosmetics below - same chrome idiom as
 * every other yield-packs screen (border filler, evenly-spaced bottom row
 * with close dead-center, {@link Page} for pagination so this scales
 * straight to a full 60-per-category roster without any layout changes).
 */
public final class CosmeticsGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 6;
    /** Four centred rows of seven under the tabs - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(4);
    private static final int[] TAB_SLOTS = {2, 4, 6};

    private final PlayerDataStore<CosmeticProfile> store;
    private final CosmeticService cosmeticService;
    private final CosmeticPopularityService popularityService;
    private final GuiManager guiManager;

    private final Map<UUID, CosmeticCategory> categoryByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pageByPlayer = new ConcurrentHashMap<>();

    public CosmeticsGui(PlayerDataStore<CosmeticProfile> store, CosmeticService cosmeticService,
                         CosmeticPopularityService popularityService, GuiManager guiManager) {
        this.store = store;
        this.cosmeticService = cosmeticService;
        this.popularityService = popularityService;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        open(player, categoryByPlayer.getOrDefault(player.getUniqueId(), CosmeticCategory.CHAT_COLOR));
    }

    private void open(Player player, CosmeticCategory category) {
        UUID uuid = player.getUniqueId();
        categoryByPlayer.put(uuid, category);
        CosmeticProfile profile = store.getOrCreate(uuid);

        List<Cosmetic> cosmetics = new ArrayList<>(cosmeticService.content().byCategory(category).values());
        Page<Cosmetic> page = Page.of(cosmetics, pageByPlayer.getOrDefault(uuid, 0), PAGE_SIZE);
        pageByPlayer.put(uuid, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Cosmetics");
        builder.fill(IntStream.range(0, 9), GuiIcons.filler());

        CosmeticCategory[] tabs = CosmeticCategory.values();
        for (int i = 0; i < tabs.length && i < TAB_SLOTS.length; i++) {
            CosmeticCategory tab = tabs[i];
            builder.item(TAB_SLOTS[i], buildTabIcon(tab, tab == category), (clicker, event) -> {
                pageByPlayer.put(uuid, 0);
                open(clicker, tab);
            });
        }

        String equippedId = category.equippedId(profile);
        List<Cosmetic> pageItems = page.items();
        int[] contentSlots = GuiLayout.centered(1, pageItems.size());
        for (int i = 0; i < pageItems.size(); i++) {
            Cosmetic cosmetic = pageItems.get(i);
            boolean equipped = cosmetic.id().equals(equippedId);
            builder.item(contentSlots[i], buildCosmeticIcon(player, cosmetic, equipped),
                    (clicker, event) -> attemptEquip(clicker, category, cosmetic, equipped));
        }

        builder.fill(IntStream.of(46, 47, 48, 50, 51, 52), GuiIcons.filler());
        builder.item(45, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, event) -> turnPage(clicker, category, page, -1));
        builder.item(49, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(53, GuiIcons.pageArrow(true, page.hasNext()), (clicker, event) -> turnPage(clicker, category, page, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, CosmeticCategory category, Page<Cosmetic> currentPage, int delta) {
        boolean canMove = delta < 0 ? currentPage.hasPrevious() : currentPage.hasNext();
        if (!canMove) {
            return;
        }
        pageByPlayer.put(player.getUniqueId(), currentPage.index() + delta);
        open(player, category);
    }

    private void attemptEquip(Player player, CosmeticCategory category, Cosmetic cosmetic, boolean currentlyEquipped) {
        if (!cosmetic.isOwnedBy(player)) {
            player.sendMessage(Text.parse("<red>You don't own <name> yet.</red>",
                    Placeholder.unparsed("name", cosmetic.displayName())));
            open(player, category);
            return;
        }
        if (currentlyEquipped) {
            cosmeticService.unequip(player, category);
            player.sendMessage(Text.parse("<gray>Unequipped <name>.</gray>",
                    Placeholder.unparsed("name", cosmetic.displayName())));
        } else {
            cosmeticService.equip(player, category, cosmetic.id());
            player.sendMessage(Text.parse("<green>Equipped <name>.</green>",
                    Placeholder.unparsed("name", cosmetic.displayName())));
        }
        open(player, category);
    }

    private ItemStack buildTabIcon(CosmeticCategory tab, boolean active) {
        Material material = switch (tab) {
            case CHAT_COLOR -> Material.OAK_SIGN;
            case NAMEPLATE -> Material.NAME_TAG;
            case TAG -> Material.PAPER;
        };
        String name = (active ? ACCENT + "&l" : "&7") + tab.displayName().toUpperCase(Locale.ROOT)
                + (active ? " &7[ᴠɪᴇᴡɪɴɢ]" : "");
        ItemBuilder builder = ItemBuilder.of(material).name(name);
        if (active) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        MenuLore.button("category", List.of(" &7Browse this cosmetic category."), ACCENT,
                "Click to View").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildCosmeticIcon(Player player, Cosmetic cosmetic, boolean equipped) {
        boolean owned = cosmetic.isOwnedBy(player);
        int owners = popularityService.countFor(cosmetic.category(), cosmetic.id());
        List<String> data = new ArrayList<>(List.of("&7Owned by: &f" + owners + " player(s)"));

        ItemBuilder builder;
        if (!owned) {
            builder = ItemBuilder.of(Material.GRAY_DYE).name("&7" + cosmetic.displayName() + " &c[Locked]");
            data.add("&7You don't have the permission");
            data.add("&7for this yet - available from");
            data.add("&7the store soon.");
            MenuLore.info("cosmetic", List.of(), ACCENT, data).forEach(builder::lore);
        } else {
            builder = ItemBuilder.of(cosmetic.icon())
                    .name(cosmetic.style() + cosmetic.displayName() + (equipped ? " &a[Equipped]" : ""));
            if (equipped) {
                builder.enchant(Enchantment.UNBREAKING, 1);
            }
            MenuLore.button("cosmetic", data, ACCENT,
                    equipped ? "Click to Unequip" : "Click to Equip"
            ).forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }
}
