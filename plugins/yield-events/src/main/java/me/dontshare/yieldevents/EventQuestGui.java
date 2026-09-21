package me.dontshare.yieldevents;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.data.EventQuest;
import me.dontshare.yieldevents.data.SeasonalEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The event screen: what is running, how long is left, what you are
 * holding, and every quest with its progress and its reward.
 * <p>
 * Claiming is a click here rather than something automatic, for the same
 * reason the milestones screen works that way - a reward that lands
 * silently while a player is looking at a cube is a reward they never saw.
 */
public final class EventQuestGui {

    private static final int QUEST_SLOTS = 45;
    private static final int HEADER_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final GuiManager guiManager;
    private final EventService eventService;

    public EventQuestGui(GuiManager guiManager, EventService eventService) {
        this.guiManager = guiManager;
        this.eventService = eventService;
    }

    public void open(Player player) {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            player.sendMessage(Text.parse("<gray>No event is running right now. Check back soon.</gray>"));
            return;
        }
        GuiBuilder builder = Gui.builder(6, Formatting.stripLeadingColorCodes(event.displayName()));
        List<EventQuest> quests = event.quests();
        int slot = 0;
        for (EventQuest quest : quests) {
            if (slot >= QUEST_SLOTS) {
                break;
            }
            builder.item(slot++, buildQuestIcon(player, event, quest),
                    (clicker, e) -> claim(clicker, event, quest));
        }
        builder.fill(IntStream.range(slot, QUEST_SLOTS), GuiIcons.filler());
        builder.fill(IntStream.range(QUEST_SLOTS, 54).filter(s -> s != HEADER_SLOT && s != CLOSE_SLOT),
                GuiIcons.filler());
        builder.item(HEADER_SLOT, buildHeaderIcon(player, event));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private void claim(Player player, SeasonalEvent event, EventQuest quest) {
        if (eventService.hasClaimed(player, event, quest)) {
            return;
        }
        if (!eventService.isComplete(player, event, quest)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        if (!eventService.claim(player, event, quest)) {
            return;
        }
        // Everything the claim itself could not pay: coins and diamonds
        // belong to yield-packs, and a potion or a pet belongs to whichever
        // plugin owns it - all of which already have an admin command, so
        // the config names one rather than this plugin growing a dependency
        // per reward type.
        for (String command : quest.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        player.sendMessage(Text.parse(
                "<" + event.color() + "><bold>" + Formatting.stripLeadingColorCodes(event.displayName())
                        + "</bold></" + event.color() + "> <dark_gray>»</dark_gray> <gray>Claimed</gray> <white><quest></white><gray>!</gray>",
                Placeholder.unparsed("quest", Formatting.stripLeadingColorCodes(quest.displayName()))));
        open(player);
    }

    private ItemStack buildQuestIcon(Player player, SeasonalEvent event, EventQuest quest) {
        long progress = eventService.progress(player, event, quest.goal());
        boolean complete = progress >= quest.target();
        boolean claimed = eventService.hasClaimed(player, event, quest);
        Material material = claimed ? Material.GRAY_DYE : complete ? Material.LIME_DYE : Material.PAPER;

        ItemBuilder builder = ItemBuilder.of(material)
                .name(MenuLore.buttonName("<" + event.color() + ">", quest.displayName()));
        List<String> lore = new ArrayList<>(quest.description());
        lore.add("");
        lore.add("&7Progress: &f" + Formatting.format((double) Math.min(progress, quest.target()))
                + "&7/&f" + Formatting.format((double) quest.target()));
        lore.add("");
        lore.add("&7Rewards:");
        if (quest.rewardCandy() > 0) {
            lore.add(" &8- &e" + Formatting.format((double) quest.rewardCandy()) + " " + event.currencyName());
        }
        if (quest.rewardCoins() > 0) {
            lore.add(" &8- &a$" + Formatting.format((double) quest.rewardCoins()));
        }
        if (quest.rewardDiamonds() > 0) {
            lore.add(" &8- &b" + Formatting.format((double) quest.rewardDiamonds()) + " diamonds");
        }
        for (String command : quest.commands()) {
            lore.add(" &8- &d" + describeCommand(command));
        }
        lore.add("");
        lore.add(claimed ? "&8Already claimed" : complete ? "&8[CLICK] &fTo Claim" : "&cNot finished yet");
        lore.forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * A reward command in words. Deliberately crude - it reads the command
     * a server owner wrote rather than a second "label" field they would
     * have to keep in sync with it, so a reward can never advertise one
     * thing and hand over another.
     */
    private String describeCommand(String command) {
        String[] parts = command.trim().split("\\s+");
        // "admin potions give <player> <stat> <multiplier> <duration>"
        if (parts.length >= 7 && parts[0].equals("admin") && parts[1].equals("potions")) {
            return "x" + parts[5] + " " + prettify(parts[4]) + " Potion";
        }
        // "admin pets give <player> <pet> <fusion> <amount>"
        if (parts.length >= 7 && parts[0].equals("admin") && parts[1].equals("pets")) {
            return prettify(parts[4].replaceFirst("^event_", ""));
        }
        return command;
    }

    private String prettify(String raw) {
        String[] words = raw.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private ItemStack buildHeaderIcon(Player player, SeasonalEvent event) {
        long days = event.daysRemaining(LocalDate.now());
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(MenuLore.infoName("<" + event.color() + ">", Formatting.stripLeadingColorCodes(event.displayName())));
        MenuLore.info("event", List.of(
                " &7Break cubes in the event zone",
                " &7to earn " + event.currencyName() + ", then hatch",
                " &7the event egg at spawn.",
                " &7Event pets earn extra " + event.currencyName() + "."),
                "<" + event.color() + ">",
                List.of(event.currencyName() + ": &f" + Formatting.format((double) eventService.balance(player, event)),
                        "Ends in: &f" + days + " day" + (days == 1 ? "" : "s"))
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
