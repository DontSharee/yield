package me.dontshare.yieldteams;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldteams.command.TeamCommand;
import me.dontshare.yieldteams.command.TeamsAdminCommand;
import me.dontshare.yieldteams.command.TeamsMenuCommand;
import me.dontshare.yieldteams.data.TeamUpgradeType;
import me.dontshare.yieldteams.data.TeamsContentLoader;
import me.dontshare.yieldteams.data.TeamsContentLoader.TeamsContent;
import me.dontshare.yieldteams.gui.TeamGui;
import me.dontshare.yieldteams.gui.TeamLeaderboardGui;
import me.dontshare.yieldteams.gui.TeamUpgradeGui;
import me.dontshare.yieldteams.gui.TeamsMenuGui;
import me.dontshare.yieldteams.listener.TeamEventListener;
import me.dontshare.yieldteams.listener.TeamJoinListener;
import me.dontshare.yieldteams.listener.TrophyInteractListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

public final class YieldTeams extends JavaPlugin {

    private static final String PROVIDER_KEY = "teams";

    private TeamsContentLoader contentLoader;
    private volatile TeamsContent content;
    private TeamStore teamStore;
    private TeamService teamService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new TeamsContentLoader(this, getLogger());
        content = contentLoader.load();

        teamStore = new TeamStore(core.getDatabaseManager(), getLogger());
        try {
            teamStore.loadAll().join();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load teams from the database", e);
        }

        teamService = new TeamService(teamStore, packs.getPlayerStore(), () -> content);
        TrophyItem trophyItem = new TrophyItem(this);

        core.getListenerManager().register(new TeamJoinListener(teamService));
        core.getListenerManager().register(new TeamEventListener(() -> content, trophyItem, packs));
        core.getListenerManager().register(new TrophyInteractListener(trophyItem, teamService));

        packs.registerCoinMultiplierProvider(PROVIDER_KEY,
                profile -> 1.0 + teamService.totalBonus(profile, TeamUpgradeType.COIN_MULTIPLIER));
        packs.registerDamageMultiplierProvider(PROVIDER_KEY,
                profile -> 1.0 + teamService.totalBonus(profile, TeamUpgradeType.DAMAGE_MULTIPLIER));
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY,
                profile -> teamService.totalBonus(profile, TeamUpgradeType.LUCK_MULTIPLIER));

        TeamUpgradeGui upgradeGui = new TeamUpgradeGui(teamService, () -> content, core.getGuiManager());
        TeamGui teamGui = new TeamGui(teamService, upgradeGui, core.getGuiManager());
        TeamLeaderboardGui leaderboardGui = new TeamLeaderboardGui(teamStore, teamGui, core.getGuiManager());
        TeamsMenuGui teamsMenuGui = new TeamsMenuGui(teamService, teamGui, leaderboardGui, core.getGuiManager());

        CommandManager.register(this, TeamCommand.build(teamService, teamGui, upgradeGui), "Team creation, membership, and info", List.of());
        CommandManager.register(this, TeamsMenuCommand.build(teamsMenuGui), "Browse teams and the team leaderboard", List.of());
        core.getAdminCommandRegistry().register(TeamsAdminCommand.build(this));
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterDamageMultiplierProvider(PROVIDER_KEY);
            packs.getLuckService().unregisterExtraLuckProvider(PROVIDER_KEY);
        }
    }

    /** Re-reads teams.yml. */
    public void reloadContent() {
        content = contentLoader.load();
    }
}
