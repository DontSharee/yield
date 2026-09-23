package me.dontshare.yieldevents;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
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
    private static final int SHOP_SLOT = 47;
    private static final int HEADER_SLOT = 49;
    private static final int CLOSE_SLOT = 51;

    private final GuiManager guiManager;
    private final EventService eventService;
    /** Set after construction - the two screens point at each other, so one has to be wired second. */
    private EventShopGui shopGui;

    public EventQuestGui(GuiManager guiManager, EventService eventService) {
        this.guiManager = guiManager;
        this.eventService = eventService;
    }

    public void setShopGui(EventShopGui shopGui) {
        this.shopGui = shopGui;
    }

    public void open(Player player) {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            player.sendMessage(Text.parse("<gray>No event is running right now. Check back soon.</gray>"));
            return;
        }
        GuiBuilder builder = Gui.builder(6, Formatting.stripLeadingColorCodes(event.displayName()));
        List<EventQuest> quests = event.quests();
        builder.fill(IntStream.range(0, QUEST_SLOTS), GuiIcons.filler());
        int[] slots = GuiLayout.centered(0, Math.min(quests.size(), GuiLayout.capacity(5)));
        for (int i = 0; i < slots.length; i++) {
            EventQuest quest = quests.get(i);
            builder.item(slots[i], buildQuestIcon(player, event, quest),
                    (clicker, e) -> claim(clicker, event, quest));
        }
        builder.fill(IntStream.range(QUEST_SLOTS, 54)
                .filter(s -> s != SHOP_SLOT && s != HEADER_SLOT && s != CLOSE_SLOT), GuiIcons.filler());
        // Hidden when the event sells nothing, rather than shown as an
        // empty room - an event with no shelf is a valid event.
        if (shopGui != null && !event.shop().isEmpty()) {
            builder.item(SHOP_SLOT, shopButton(event), (clicker, e) -> shopGui.open(clicker));
        }
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
        String accent = "<" + event.color() + ">";
        List<String> data = new ArrayList<>();
        data.add("Progress: " + MenuLore.progress(Math.min(progress, quest.target()), quest.target()));
        if (quest.rewardCandy() > 0) {
            data.add("Reward: &e" + Formatting.format((double) quest.rewardCandy()) + " " + event.currencyName());
        }
        if (quest.rewardCoins() > 0) {
            data.add("Reward: &6" + Formatting.format((double) quest.rewardCoins()) + " &7coins");
        }
        if (quest.rewardDiamonds() > 0) {
            data.add("Reward: &b" + Formatting.format((double) quest.rewardDiamonds()) + " &7diamonds");
        }
        for (String command : quest.commands()) {
            data.add("Reward: &d" + RewardText.describeCommand(command));
        }
        if (complete && !claimed) {
            MenuLore.button("event quest", quest.description(), accent, data, "Click to claim").forEach(builder::lore);
        } else {
            List<String> description = new ArrayList<>(quest.description());
            description.add(claimed ? "&8Already claimed." : "&cNot finished yet.");
            MenuLore.info("event quest", description, accent, data).forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    private ItemStack shopButton(SeasonalEvent event) {
        ItemBuilder builder = ItemBuilder.of(Material.CHEST)
                .name(MenuLore.buttonName("<" + event.color() + ">", "SHOP"));
        MenuLore.button("shop", List.of(" &7Spend your " + event.currencyName() + " on", " &7something you pick."),
                "<" + event.color() + ">", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildHeaderIcon(Player player, SeasonalEvent event) {
        long days = event.daysRemaining(LocalDate.now());
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(MenuLore.infoName("<" + event.color() + ">", Formatting.stripLeadingColorCodes(event.displayName())));
        MenuLore.info("event", List.of(
                " &7Break cubes in the event zone",
                " &7to earn " + event.currencyName() + ", then hatch",
                " &7the event egg at spawn or",
                " &7spend it in the shop.",
                " &7Event pets earn extra " + event.currencyName() + "."),
                "<" + event.color() + ">",
                List.of(event.currencyName() + ": &f" + Formatting.format((double) eventService.balance(player, event)),
                        "Ends in: &f" + days + " day" + (days == 1 ? "" : "s"))
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
