package me.dontshare.yieldteams.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldteams.TeamService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.IntStream;

/** The /teams hub - "My Team" and "Leaderboard", the entry point into everything else team-related. */
public final class TeamsMenuGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int MY_TEAM_SLOT = 11;
    private static final int LEADERBOARD_SLOT = 15;
    private static final int CLOSE_SLOT = 22;

    private final TeamService teamService;
    private final TeamGui teamGui;
    private final TeamLeaderboardGui leaderboardGui;
    private final GuiManager guiManager;

    public TeamsMenuGui(TeamService teamService, TeamGui teamGui, TeamLeaderboardGui leaderboardGui, GuiManager guiManager) {
        this.teamService = teamService;
        this.teamGui = teamGui;
        this.leaderboardGui = leaderboardGui;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        GuiBuilder builder = Gui.builder(3, "Teams")
                .fill(IntStream.range(0, 27), GuiIcons.filler())
                .item(MY_TEAM_SLOT, buildMyTeamIcon(player), (clicker, event) -> teamGui.open(clicker))
                .item(LEADERBOARD_SLOT, buildLeaderboardIcon(), (clicker, event) -> leaderboardGui.open(clicker))
                .item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private ItemStack buildMyTeamIcon(Player player) {
        var team = teamService.teamOf(player);
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName(ACCENT, "MY TEAM"));
        List<String> data = team != null
                ? List.of(" &7Team: &f" + Formatting.stripLeadingColorCodes(team.getName()),
                          " &7Trophies: &6" + Formatting.format(team.getTrophyBalance()))
                : List.of(" &7You're not in a team yet -", " &7use /team create followed by a name.");
        MenuLore.button("team", data, ACCENT, "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildLeaderboardIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.GOLDEN_HELMET).name(MenuLore.buttonName(ACCENT, "LEADERBOARD"));
        MenuLore.button("leaderboard", List.of(" &7See every team ranked", " &7by trophy balance."),
                ACCENT, "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
