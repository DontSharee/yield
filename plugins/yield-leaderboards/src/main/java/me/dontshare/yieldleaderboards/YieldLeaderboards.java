package me.dontshare.yieldleaderboards;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldleaderboards.command.LeaderboardsAdminCommand;
import me.dontshare.yieldleaderboards.data.LeaderboardContentLoader;
import org.bukkit.plugin.java.JavaPlugin;

public final class YieldLeaderboards extends JavaPlugin {

    private LeaderboardContentLoader contentLoader;
    private volatile LeaderboardContentLoader.Content content;
    private LeaderboardService leaderboardService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        contentLoader = new LeaderboardContentLoader(this, getLogger());
        content = contentLoader.load();

        leaderboardService = new LeaderboardService(this, () -> content, core.getDatabaseManager());
        leaderboardService.start();

        core.getAdminCommandRegistry().register(LeaderboardsAdminCommand.build(this));
    }

    /** Re-reads stats.yml/leaderboards.yml - existing timers keep running against the new definitions. */
    public void reloadContent() {
        content = contentLoader.load();
    }

    public LeaderboardContentLoader.Content getContent() {
        return content;
    }

    public LeaderboardService getLeaderboardService() {
        return leaderboardService;
    }
}
