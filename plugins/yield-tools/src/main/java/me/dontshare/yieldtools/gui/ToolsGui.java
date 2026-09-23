package me.dontshare.yieldtools.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldtools.ToolService;
import me.dontshare.yieldtools.data.ToolDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

/**
 * /tools - the whole path at once: what you have, the one you can buy
 * next, and everything after it, so the next few are always in view as
 * something to aim for.
 * <p>
 * Buy Next buys one; Buy Max buys as far as your coins reach. The path is
 * the only order there is, so neither ever has to ask which tool.
 */
public final class ToolsGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int ROWS = 6;
    private static final int HEADER_SLOT = 4;
    private static final int BUY_NEXT_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int BUY_MAX_SLOT = 51;
    /** The inner 7x3 of the chest - room for 21 tools. */
    private static final int[] PATH_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    private final ToolService tools;
    private final YieldPacks packs;
    private final GuiManager guiManager;
    /** What one tap deals right now, bare-handed - supplied by yield-zones' TapService. */
    private final ToLongFunction<Player> bareTapDamage;

    public ToolsGui(ToolService tools, YieldPacks packs, GuiManager guiManager, ToLongFunction<Player> bareTapDamage) {
        this.tools = tools;
        this.packs = packs;
        this.guiManager = guiManager;
        this.bareTapDamage = bareTapDamage;
    }

    public void open(Player player) {
        // Also how a player whose inventory was full gets their tool back.
        tools.refreshItem(player);
        List<ToolDefinition> path = tools.all();
        int owned = tools.ownedIndex(player);
        BigInteger coins = packs.getPlayerStore().getOrCreate(player.getUniqueId()).getCoins();

        GuiBuilder builder = Gui.builder(ROWS, "Tools");
        builder.fill(IntStream.range(0, ROWS * 9), GuiIcons.filler());
        for (int i = 0; i < path.size() && i < PATH_SLOTS.length; i++) {
            builder.item(PATH_SLOTS[i], pathIcon(path.get(i), owned, coins));
        }
        builder.item(HEADER_SLOT, headerIcon(player));
        builder.item(BUY_NEXT_SLOT, buyNextIcon(player, coins), (clicker, e) -> buyNext(clicker));
        builder.item(BUY_MAX_SLOT, buyMaxIcon(player, coins), (clicker, e) -> buyMax(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private void buyNext(Player player) {
        ToolDefinition next = tools.next(player);
        switch (tools.buyNext(player)) {
            case SUCCESS -> celebrate(player, next, 1);
            case TOO_POOR -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<gray>You need <price> coins for that.</gray>",
                        Placeholder.unparsed("price", Formatting.format(BigInteger.valueOf(next.costCoins())))));
            }
            case MAXED -> player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
        open(player);
    }

    private void buyMax(Player player) {
        int bought = tools.buyMax(player);
        if (bought == 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            celebrate(player, tools.current(player), bought);
        }
        open(player);
    }

    private void celebrate(Player player, ToolDefinition tool, int count) {
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        String what = count == 1 ? "Unlocked" : "Unlocked " + count + " tools - now holding";
        player.sendMessage(Text.parse("<#4BD9FF>⚒</#4BD9FF> <gray>" + what + "</gray> <tool><gray>!</gray> "
                        + "<dark_gray>Hold it to tap harder.</dark_gray>",
                Placeholder.component("tool", Text.parse(tool.displayName()))));
    }

    private ItemStack pathIcon(ToolDefinition tool, int owned, BigInteger coins) {
        boolean isOwned = tool.index() <= owned;
        boolean isCurrent = tool.index() == owned;
        boolean isNext = tool.index() == owned + 1;
        String name = isOwned || isNext ? tool.displayName() : "&8" + Formatting.stripLeadingColorCodes(tool.displayName());
        ItemBuilder builder = ItemBuilder.of(tool.material()).name(name);
        tool.description().forEach(builder::lore);
        builder.lore("");
        builder.lore("&7Taps: &ax" + Formatting.format(tool.tapMultiplier()) + " &7damage while held");
        if (isCurrent) {
            builder.lore("");
            builder.lore("&a&lEQUIPPED &7- your best tool");
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        } else if (isOwned) {
            builder.lore("");
            builder.lore("&8Owned");
        } else {
            BigInteger cost = BigInteger.valueOf(tool.costCoins());
            builder.lore("&7Cost: &e" + Formatting.format(cost) + " &7coins");
            builder.lore("");
            if (isNext) {
                builder.lore(coins.compareTo(cost) >= 0 ? "&8Use Buy Next below" : "&cYou can't afford this yet.");
            } else {
                builder.lore("&8Unlock the tool before it first.");
            }
        }
        return builder.hideAttributes().build();
    }

    private ItemStack headerIcon(Player player) {
        ToolDefinition current = tools.current(player);
        long bare = bareTapDamage.applyAsLong(player);
        double multiplier = current == null ? 1.0 : current.tapMultiplier();
        ItemBuilder builder = ItemBuilder.of(current == null ? Material.BARRIER : current.material())
                .name(MenuLore.infoName(ACCENT, "TOOLS"));
        MenuLore.info("tools", List.of(
                        " &7Every click on a cube is a tap.",
                        " &7Hold your tool and your taps",
                        " &7hit harder. Each tool needs",
                        " &7the one before it."),
                ACCENT,
                List.of("Holding: " + (current == null ? "&7nothing yet" : current.displayName()),
                        "Tap damage: &f" + Formatting.format(Math.round(bare * multiplier))
                                + (current == null ? "" : " &8(x" + Formatting.format(multiplier) + ")")))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buyNextIcon(Player player, BigInteger coins) {
        ToolDefinition next = tools.next(player);
        if (next == null) {
            return ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName(ACCENT, "ALL TOOLS OWNED"))
                    .lore("&7You're at the end of the path.").hideAttributes().build();
        }
        BigInteger cost = BigInteger.valueOf(next.costCoins());
        boolean affordable = coins.compareTo(cost) >= 0;
        ItemBuilder builder = ItemBuilder.of(affordable ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, "BUY NEXT"));
        builder.lore("&7Next: " + next.displayName());
        builder.lore("&7Cost: &e" + Formatting.format(cost) + " &7coins");
        builder.lore("");
        builder.lore(affordable ? "&8[CLICK] &fTo Buy" : "&cYou can't afford this yet.");
        return builder.hideAttributes().build();
    }

    /** Shows exactly how far Buy Max would get before it is pressed - no surprise spending. */
    private ItemStack buyMaxIcon(Player player, BigInteger coins) {
        List<ToolDefinition> path = tools.all();
        int index = tools.ownedIndex(player);
        BigInteger left = coins;
        int count = 0;
        BigInteger spend = BigInteger.ZERO;
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
                .name(MenuLore.buttonName(ACCENT, "BUY MAX"));
        if (count == 0) {
            builder.lore(tools.next(player) == null ? "&7You own every tool." : "&cYou can't afford the next tool yet.");
        } else {
            builder.lore("&7Buys &f" + count + " &7tool" + (count == 1 ? "" : "s") + ", up to "
                    + path.get(index).displayName());
            builder.lore("&7Total: &e" + Formatting.format(spend) + " &7coins");
            builder.lore("");
            builder.lore("&8[CLICK] &fTo Buy");
        }
        return builder.hideAttributes().build();
    }
}
