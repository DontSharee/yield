package me.dontshare.yieldteams.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldteams.TeamService;
import me.dontshare.yieldteams.data.Team;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Team info + member list (as real player heads), with a shortcut into
 * {@link TeamUpgradeGui}. Membership actions (invite/kick/leave/disband)
 * stay command-driven - this screen is read-only.
 * <p>
 * {@link #open(Player, Team)} shows any team, not just the viewer's own -
 * {@link TeamLeaderboardGui} opens other players' teams this way. The
 * Upgrades button only appears when the viewer is actually looking at
 * their own team, since spending is only ever done from the team's own
 * shared balance.
 */
public final class TeamGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int INFO_SLOT = 4;
    private static final int UPGRADES_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int MEMBER_START_SLOT = 9;

    private final TeamService teamService;
    private final TeamUpgradeGui upgradeGui;
    private final GuiManager guiManager;

    public TeamGui(TeamService teamService, TeamUpgradeGui upgradeGui, GuiManager guiManager) {
        this.teamService = teamService;
        this.upgradeGui = upgradeGui;
        this.guiManager = guiManager;
    }

    /** Opens the viewer's own team, or messages them if they're not in one. */
    public void open(Player player) {
        Team team = teamService.teamOf(player);
        if (team == null) {
            player.sendMessage(Text.parse("<red>You're not in a team - use /team create followed by a name.</red>"));
            return;
        }
        open(player, team);
    }

    public void open(Player viewer, Team team) {
        boolean ownTeam = team.equals(teamService.teamOf(viewer));

        GuiBuilder builder = Gui.builder(6, "Team " + Formatting.stripLeadingColorCodes(team.getName()))
                .fill(IntStream.range(0, 9), GuiIcons.filler())
                .fill(IntStream.range(45, 54), GuiIcons.filler())
                .item(INFO_SLOT, buildInfoIcon(team))
                .item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        if (ownTeam) {
            builder.item(UPGRADES_SLOT, buildUpgradesButton(), (clicker, event) -> upgradeGui.open(clicker));
        }

        List<java.util.UUID> members = team.getMemberIds();
        for (int i = 0; i < members.size() && MEMBER_START_SLOT + i < 45; i++) {
            builder.item(MEMBER_START_SLOT + i, buildMemberIcon(team, members.get(i)));
        }

        guiManager.open(viewer, builder.build());
    }

    private ItemStack buildInfoIcon(Team team) {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.infoName(ACCENT, Formatting.stripLeadingColorCodes(team.getName())));
        List<String> data = List.of(
                "Members: &f" + team.getMemberIds().size(),
                "Trophies: &6" + Formatting.format(team.getTrophyBalance()),
                "Leader: &f" + Bukkit.getOfflinePlayer(team.getLeaderId()).getName());
        MenuLore.info("team", List.of(), ACCENT, data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildUpgradesButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ANVIL).name(MenuLore.buttonName(ACCENT, "TEAM UPGRADES"));
        MenuLore.button("upgrades", List.of(" &7Spend your team's trophies", " &7on upgrades for everyone."),
                ACCENT, "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildMemberIcon(Team team, java.util.UUID memberId) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(memberId);
        boolean isLeader = team.getLeaderId().equals(memberId);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(offline);
            head.setItemMeta(skullMeta);
        }
        ItemBuilder builder = ItemBuilder.of(head).name((isLeader ? "&6&l★ " : "&f") + offline.getName());
        List<String> data = List.of(
                isLeader ? "&7Team Leader" : "&7Member",
                offline.isOnline() ? "&aOnline" : "&7Offline");
        MenuLore.info("member", List.of(), ACCENT, data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
