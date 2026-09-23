package me.dontshare.yieldteams.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldteams.TeamService;
import me.dontshare.yieldteams.data.Team;
import me.dontshare.yieldteams.data.TeamUpgrade;
import me.dontshare.yieldteams.data.TeamsContentLoader.TeamsContent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** The team-upgrade tree - spent from the team's shared trophy balance, benefiting every current member. */
public final class TeamUpgradeGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int BALANCE_SLOT = 4;
    private static final int GRID_START = 10;

    private final TeamService teamService;
    private final Supplier<TeamsContent> content;
    private final GuiManager guiManager;

    public TeamUpgradeGui(TeamService teamService, Supplier<TeamsContent> content, GuiManager guiManager) {
        this.teamService = teamService;
        this.content = content;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        Team team = teamService.teamOf(player);
        if (team == null) {
            player.sendMessage(Text.parse("<red>You're not in a team.</red>"));
            return;
        }

        GuiBuilder builder = Gui.builder(4, "Team Upgrades")
                .fill(IntStream.range(0, 9), GuiIcons.filler())
                .fill(IntStream.range(27, 36), GuiIcons.filler())
                .item(BALANCE_SLOT, buildBalanceIcon(team))
                .item(31, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        List<TeamUpgrade> upgrades = new ArrayList<>(content.get().upgrades().values());
        int[] slots = GuiLayout.centered(GRID_START / 9, Math.min(upgrades.size(), GuiLayout.capacity(2)));
        for (int i = 0; i < slots.length; i++) {
            TeamUpgrade upgrade = upgrades.get(i);
            builder.item(slots[i], buildUpgradeIcon(team, upgrade), (clicker, event) -> attemptBuy(clicker, upgrade.id()));
        }

        guiManager.open(player, builder.build());
    }

    private void attemptBuy(Player player, String upgradeId) {
        TeamService.UpgradeResult result = teamService.buyUpgrade(player, upgradeId);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                player.sendMessage(Text.parse("<green>Upgrade purchased!</green>"));
            }
            case INSUFFICIENT_TROPHIES -> player.sendMessage(Text.parse("<red>Your team doesn't have enough trophies.</red>"));
            case MAXED -> player.sendMessage(Text.parse("<red>This upgrade is already maxed.</red>"));
            case NOT_IN_TEAM -> player.sendMessage(Text.parse("<red>You're not in a team.</red>"));
            case UNKNOWN_UPGRADE -> {
            }
        }
        open(player);
    }

    private ItemStack buildBalanceIcon(Team team) {
        ItemBuilder builder = ItemBuilder.of(Material.SUNFLOWER).name(MenuLore.infoName(ACCENT, "TROPHY BALANCE"));
        MenuLore.info("balance", List.of(), ACCENT, List.of("Trophies: &6" + Formatting.format(team.getTrophyBalance())))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildUpgradeIcon(Team team, TeamUpgrade upgrade) {
        int level = team.getUpgradeLevels().getOrDefault(upgrade.id(), 0);
        boolean maxed = level >= upgrade.maxLevel();
        String name = Formatting.stripLeadingColorCodes(upgrade.displayName());

        ItemBuilder builder = ItemBuilder.of(upgrade.icon());
        if (!maxed) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }

        List<String> data = new ArrayList<>(List.of(
                "&7Level: " + MenuLore.progress(level, upgrade.maxLevel()),
                "&7Effect: &a+" + Formatting.format(level * upgrade.valuePerLevel() * 100) + "%"
        ));
        if (maxed) {
            builder.name(MenuLore.name("&a", name) + " &7[MAXED]");
            MenuLore.info("upgrade", List.of(), ACCENT, data).forEach(builder::lore);
            return builder.hideAttributes().build();
        }

        BigInteger cost = upgrade.costs().get(level);
        data.add("&7Cost: &6" + Formatting.format(cost) + " trophies");
        builder.name(MenuLore.buttonName(ACCENT, name.toUpperCase(java.util.Locale.ROOT)));
        MenuLore.button("upgrade", data, ACCENT, "Click to Buy").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
