package me.dontshare.yieldteams.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldteams.TeamStore;
import me.dontshare.yieldteams.data.Team;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Every team ranked by trophy balance, descending - each row's icon is the leader's own head. Click a row to view that team's full member list (see {@link TeamGui}). */
public final class TeamLeaderboardGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int MAX_ROWS = 4;
    private static final int GRID_START = 9;
    private static final int INFO_SLOT = 4;

    private final TeamStore teamStore;
    private final TeamGui teamGui;
    private final GuiManager guiManager;

    public TeamLeaderboardGui(TeamStore teamStore, TeamGui teamGui, GuiManager guiManager) {
        this.teamStore = teamStore;
        this.teamGui = teamGui;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        List<Team> ranked = new ArrayList<>();
        teamStore.all().forEach(ranked::add);
        ranked.sort(Comparator.comparing(Team::getTrophyBalance).reversed());

        int gridSlots = Math.min(ranked.size(), GuiLayout.capacity(MAX_ROWS));
        int middleRows = Math.max(1, (int) Math.ceil(gridSlots / (double) GuiLayout.INNER_WIDTH));
        int rows = Math.min(6, middleRows + 2);

        GuiBuilder builder = Gui.builder(rows, "Team Leaderboard")
                .fill(IntStream.range(0, 9), GuiIcons.filler())
                .fill(IntStream.range((rows - 1) * 9, rows * 9), GuiIcons.filler())
                .item(INFO_SLOT, buildInfoIcon(ranked.size()))
                .item((rows - 1) * 9 + 4, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        int[] slots = GuiLayout.centered(GRID_START / 9, gridSlots);
        for (int i = 0; i < slots.length; i++) {
            Team team = ranked.get(i);
            int rank = i + 1;
            builder.item(slots[i], buildTeamIcon(team, rank), (clicker, event) -> teamGui.open(clicker, team));
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack buildInfoIcon(int teamCount) {
        ItemBuilder builder = ItemBuilder.of(Material.GOLDEN_HELMET).name(MenuLore.infoName(ACCENT, "TEAM LEADERBOARD"));
        List<String> description = List.of("&7Ranked by trophy balance.", "&7" + teamCount + " team(s) total.");
        MenuLore.info("leaderboard", description, ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildTeamIcon(Team team, int rank) {
        OfflinePlayer leader = Bukkit.getOfflinePlayer(team.getLeaderId());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(leader);
            head.setItemMeta(skullMeta);
        }
        String rankColor = switch (rank) {
            case 1 -> "&6";
            case 2 -> "&7";
            case 3 -> "&c";
            default -> "&f";
        };
        ItemBuilder builder = ItemBuilder.of(head)
                .name(rankColor + "&l#" + rank + " " + Formatting.stripLeadingColorCodes(team.getName()));
        List<String> data = List.of(
                "Trophies: &6" + Formatting.format(team.getTrophyBalance()),
                "Members: &f" + team.getMemberIds().size(),
                "Leader: &f" + leader.getName());
        MenuLore.purchase("team", List.of(), ACCENT, data, "Click to View Members").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
