package me.dontshare.yieldtools.gui;

import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldtools.ToolService;
import me.dontshare.yieldtools.data.ToolDefinition;
import me.dontshare.yieldzones.cube.TapService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /tools (/weapons) - the whole weapon path, a page at a time.
 * <p>
 * The weapons ARE the buttons: click the next one to buy it. Your current
 * weapon glows; the ones you own are marked; the one you can buy says its
 * price; everything past it is locked until the one before it is yours.
 * Every entry shows the same three facts - damage, price, state - and
 * nothing else, so 120 weapons read as one list rather than 120 blurbs.
 * <p>
 * It opens on the page with your next weapon on it, because that is the
 * only one you can do anything with.
 */
public final class ToolsGui {

    /** Four centred rows of seven inside the border (see GuiLayout). */
    private static final int CONTENT_SLOTS = GuiLayout.capacity(4);
    private static final int CONTENT_FIRST_ROW = 1;
    /** The top row carries the header and Buy Max, either side of its middle. */
    private static final int HEADER_SLOT = 3;
    private static final int BUY_MAX_SLOT = 5;
    private static final int PREV_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 50;

    private final ToolService tools;
    private final YieldPacks packs;
    private final GuiManager guiManager;
    private final TapService taps;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public ToolsGui(ToolService tools, YieldPacks packs, GuiManager guiManager, TapService taps) {
        this.tools = tools;
        this.packs = packs;
        this.guiManager = guiManager;
        this.taps = taps;
    }

    /** Opens on the page holding the player's next weapon. */
    public void open(Player player) {
        int focus = Math.max(0, tools.ownedIndex(player) + 1);
        pageIndex.put(player.getUniqueId(), Math.min(focus, Math.max(0, tools.all().size() - 1)) / CONTENT_SLOTS);
        render(player);
    }

    private void render(Player player) {
        // Also how a player whose inventory was full gets their weapon back.
        tools.refreshItem(player);
        List<ToolDefinition> path = tools.all();
        int owned = tools.ownedIndex(player);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        BigInteger coins = profile.getCoins();
        Page<ToolDefinition> page = Page.of(path, pageIndex.getOrDefault(player.getUniqueId(), 0), CONTENT_SLOTS);

        GuiBuilder builder = Gui.builder(6, page.totalPages() > 1
                ? "Weapons (" + (page.index() + 1) + "/" + page.totalPages() + ")" : "Weapons");
        builder.fill(GuiLayout.all(6), GuiIcons.filler());
        int[] slots = GuiLayout.centered(CONTENT_FIRST_ROW, page.items().size());
        for (int i = 0; i < slots.length; i++) {
            ToolDefinition tool = page.items().get(i);
            builder.item(slots[i], weaponIcon(player, profile, tool, owned, coins), (clicker, e) -> clicked(clicker, tool));
        }
        if (page.hasPrevious()) {
            builder.item(PREV_SLOT, GuiIcons.pageArrow(false, true), (clicker, e) -> turnPage(clicker, -1));
        }
        if (page.hasNext()) {
            builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, true), (clicker, e) -> turnPage(clicker, 1));
        }
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(HEADER_SLOT, headerIcon(player, profile));
        builder.item(BUY_MAX_SLOT, buyMaxIcon(player, coins), (clicker, e) -> buyMax(clicker));
        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        int pages = Math.max(1, (tools.all().size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        int next = Math.max(0, Math.min(pages - 1, pageIndex.getOrDefault(player.getUniqueId(), 0) + delta));
        pageIndex.put(player.getUniqueId(), next);
        render(player);
    }

    /** Clicking the next weapon buys it; clicking anything else explains why it can't be. */
    private void clicked(Player player, ToolDefinition tool) {
        int owned = tools.ownedIndex(player);
        if (tool.index() <= owned) {
            return;
        }
        if (tool.index() > owned + 1) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            player.sendMessage(Text.parse("<gray>Unlock the weapon before it first.</gray>"));
            return;
        }
        if (tools.buyNext(player) == ToolService.BuyResult.SUCCESS) {
            celebrate(player, tool, 1);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            player.sendMessage(Text.parse("<gray>You need <price> coins for that.</gray>",
                    Placeholder.unparsed("price", Formatting.format(BigInteger.valueOf(tool.costCoins())))));
        }
        render(player);
    }

    private void buyMax(Player player) {
        int bought = tools.buyMax(player);
        if (bought == 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            render(player);
            return;
        }
        celebrate(player, tools.current(player), bought);
        // Jump to where the path now stands, not wherever they were browsing.
        open(player);
    }

    private void celebrate(Player player, ToolDefinition tool, int count) {
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        String what = count == 1 ? "Unlocked" : "Unlocked " + count + " weapons - now holding";
        player.sendMessage(Text.parse("<#4BD9FF>⚔</#4BD9FF> <gray>" + what + "</gray> <weapon><gray>.</gray>",
                Placeholder.component("weapon", Text.parse(tool.displayName()))));
    }

    private ItemStack weaponIcon(Player player, PackPlayerProfile profile, ToolDefinition tool, int owned,
                                 BigInteger coins) {
        boolean current = tool.index() == owned;
        boolean isOwned = tool.index() <= owned;
        boolean isNext = tool.index() == owned + 1;
        BigInteger cost = BigInteger.valueOf(tool.costCoins());

        String name = isOwned || isNext ? tool.displayName() : "&8" + Formatting.stripLeadingColorCodes(tool.displayName());
        ItemBuilder builder = ItemBuilder.of(tool.material()).name(name);
        builder.lore("&7Damage: &f" + Formatting.format(tool.power()) + "x &7pet power &8("
                + Formatting.format((double) taps.tapDamageAt(player, profile, tool.power())) + ")");
        if (!isOwned) {
            builder.lore("&7Cost: &e" + Formatting.format(cost) + " &7coins");
        }
        builder.lore("");
        if (current) {
            builder.lore("&aEquipped");
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        } else if (isOwned) {
            builder.lore("&8Owned");
        } else if (isNext) {
            builder.lore(coins.compareTo(cost) >= 0 ? "&8[CLICK] &fTo Buy" : "&cYou can't afford this yet.");
        } else {
            builder.lore("&8Locked");
        }
        return builder.hideAttributes().build();
    }

    private ItemStack headerIcon(Player player, PackPlayerProfile profile) {
        ToolDefinition current = tools.current(player);
        double power = current == null ? TapService.BARE_TAP_POWER : current.power();
        return ItemBuilder.of(current == null ? Material.BARRIER : current.material())
                .name("<#4BD9FF><bold>WEAPONS</bold>")
                .lore("&7Holding: " + (current == null ? "&7bare hands" : current.displayName()))
                .lore("&7Pet power: &f" + Formatting.format(taps.petPower(profile)))
                .lore("&7Tap damage: &f" + Formatting.format((double) taps.tapDamageAt(player, profile, power))
                        + " &8(" + Formatting.format(power) + "x)")
                .lore("")
                .lore("&7Owned: " + MenuLore.progress(tools.ownedIndex(player) + 1, tools.all().size()))
                .hideAttributes()
                .build();
    }

    /** Shows exactly how far Buy Max would get before it is pressed - no surprise spending. */
    private ItemStack buyMaxIcon(Player player, BigInteger coins) {
        List<ToolDefinition> path = tools.all();
        int index = tools.ownedIndex(player);
        BigInteger left = coins;
        BigInteger spend = BigInteger.ZERO;
        int count = 0;
        while (index + 1 < path.size()) {
            BigInteger cost = BigInteger.valueOf(path.get(index + 1).costCoins());
            if (left.compareTo(cost) < 0) {
                break;
            }
            left = left.subtract(cost);
            spend = spend.add(cost);
            index++;
            count++;
        }
        ItemBuilder builder = ItemBuilder.of(count > 0 ? Material.EMERALD_BLOCK : Material.GRAY_DYE)
                .name("<#4BD9FF><bold>BUY MAX</bold>");
        if (count == 0) {
            builder.lore(tools.next(player) == null ? "&7You own every weapon." : "&cYou can't afford the next weapon yet.");
        } else {
            builder.lore("&7Buys &f" + count + " &7weapon" + (count == 1 ? "" : "s") + ", up to " + path.get(index).displayName());
            builder.lore("&7Total: &e" + Formatting.format(spend) + " &7coins");
            builder.lore("");
            builder.lore("&8[CLICK] &fTo Buy");
        }
        return builder.hideAttributes().build();
    }
}
