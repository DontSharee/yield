package me.dontshare.yieldevents;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.data.EventShopEntry;
import me.dontshare.yieldevents.data.SeasonalEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The shelf next to the egg: what an event's currency buys when a player
 * would rather decide than roll.
 * <p>
 * The egg answers "spend a little, many times". This answers "spend a lot,
 * once", which is the half the event was missing - a fortnight of a
 * seasonal currency with one sink means a player who already has the pets
 * they wanted is earning nothing they can use, and a player who has been
 * unlucky has no way to convert effort into the pet the odds never gave
 * them. Per-player stock is what keeps the two from collapsing into each
 * other: a limit of 1 is a pity buy, a limit of 3 is exactly enough to
 * fuse, and no limit is for consumables where repeat buying is the point.
 * <p>
 * Reached from the quest screen rather than a command of its own, because
 * the two are one thing to a player: what the event is asking of them, and
 * what it gives back.
 */
public final class EventShopGui {

    private static final int ENTRY_SLOTS = 45;
    private static final int BACK_SLOT = 45;
    private static final int HEADER_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final GuiManager guiManager;
    private final EventService eventService;
    /** Set after construction - the two screens point at each other, so one has to be wired second. */
    private EventQuestGui questGui;

    public EventShopGui(GuiManager guiManager, EventService eventService) {
        this.guiManager = guiManager;
        this.eventService = eventService;
    }

    public void setQuestGui(EventQuestGui questGui) {
        this.questGui = questGui;
    }

    public void open(Player player) {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            player.sendMessage(Text.parse("<gray>No event is running right now. Check back soon.</gray>"));
            return;
        }
        GuiBuilder builder = Gui.builder(6,
                Formatting.stripLeadingColorCodes(event.displayName()) + " Shop");
        int slot = 0;
        for (EventShopEntry entry : event.shop()) {
            if (slot >= ENTRY_SLOTS) {
                break;
            }
            builder.item(slot++, buildEntryIcon(player, event, entry), (clicker, e) -> buy(clicker, event, entry));
        }
        builder.fill(IntStream.range(slot, ENTRY_SLOTS), GuiIcons.filler());
        builder.fill(IntStream.range(ENTRY_SLOTS, 54)
                .filter(s -> s != BACK_SLOT && s != HEADER_SLOT && s != CLOSE_SLOT), GuiIcons.filler());
        if (questGui != null) {
            builder.item(BACK_SLOT, backButton(event), (clicker, e) -> questGui.open(clicker));
        }
        builder.item(HEADER_SLOT, buildHeaderIcon(player, event));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    /**
     * The whole transaction. {@link EventService#buy} takes the currency
     * and records the stock in one saved step BEFORE the commands run, so
     * a rapid double-click cannot buy a one-per-player entry twice: the
     * second click finds the stock already spent.
     */
    private void buy(Player player, SeasonalEvent event, EventShopEntry entry) {
        EventService.BuyResult result = eventService.buy(player, event, entry);
        if (result != EventService.BuyResult.SUCCESS) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            if (result == EventService.BuyResult.TOO_POOR) {
                player.sendMessage(Text.parse(
                        "<gray>You need <price> <currency> for that.</gray>",
                        Placeholder.unparsed("price", Formatting.format((double) entry.price())),
                        Placeholder.unparsed("currency", event.currencyName())));
            }
            return;
        }
        for (String command : entry.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        player.sendMessage(Text.parse(
                "<" + event.color() + "><bold>" + Formatting.stripLeadingColorCodes(event.displayName())
                        + "</bold></" + event.color() + "> <dark_gray>»</dark_gray> <gray>Bought</gray> <white><item></white><gray>!</gray>",
                Placeholder.unparsed("item", Formatting.stripLeadingColorCodes(entry.displayName()))));
        open(player);
    }

    private ItemStack buildEntryIcon(Player player, SeasonalEvent event, EventShopEntry entry) {
        int bought = eventService.bought(player, event, entry);
        int left = entry.remaining(bought);
        boolean soldOut = left <= 0;
        boolean affordable = eventService.balance(player, event) >= entry.price();

        ItemBuilder builder = ItemBuilder.of(soldOut ? Material.GRAY_DYE : entry.material())
                .name(MenuLore.buttonName("<" + event.color() + ">", entry.displayName()));
        // Generated facts only - what it gives, what it costs, how many are
        // left. No per-item blurb: every shop entry reads the same way.
        List<String> lore = new ArrayList<>();
        lore.add("&7You get:");
        for (String command : entry.commands()) {
            lore.add(" &8- &d" + RewardText.describeCommand(command));
        }
        lore.add("");
        lore.add("&7Price: &e" + Formatting.format((double) entry.price()) + " &7" + event.currencyName());
        // Stock is the reason to buy now rather than later, so it is said
        // plainly even when there is plenty - a shelf that only mentions a
        // limit once it is gone has told the player too late.
        if (!entry.unlimited()) {
            lore.add("&7Stock: &f" + left + "&7/&f" + entry.limit());
        }
        lore.add("");
        lore.add(soldOut ? "&8Sold out" : affordable ? "&8[CLICK] &fTo Buy" : "&cYou can't afford this yet.");
        lore.forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack backButton(SeasonalEvent event) {
        ItemBuilder builder = ItemBuilder.of(Material.PAPER)
                .name(MenuLore.buttonName("<" + event.color() + ">", "QUESTS"));
        MenuLore.button("quests", List.of(" &7What the event is asking", " &7of you, and what it pays."),
                "<" + event.color() + ">", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildHeaderIcon(Player player, SeasonalEvent event) {
        ItemBuilder builder = ItemBuilder.of(Material.CHEST)
                .name(MenuLore.infoName("<" + event.color() + ">", "SHOP"));
        MenuLore.info("shop", List.of(
                " &7Spend your " + event.currencyName() + " on",
                " &7something you pick, instead",
                " &7of something you roll for.",
                " &7Most of it is stocked once."),
                "<" + event.color() + ">",
                List.of(event.currencyName() + ": &f"
                        + Formatting.format((double) eventService.balance(player, event)))
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
