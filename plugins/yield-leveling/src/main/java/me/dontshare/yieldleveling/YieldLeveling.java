package me.dontshare.yieldleveling;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldleveling.command.LevelingAdminCommand;
import me.dontshare.yieldleveling.data.PlayerLevelingConfig;
import me.dontshare.yieldleveling.data.PlayerLevelingContentLoader;
import me.dontshare.yieldleveling.listener.PlayerLevelingListener;
import me.dontshare.yieldpacks.YieldPacks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class YieldLeveling extends JavaPlugin {

    private PlayerLevelingContentLoader contentLoader;
    private volatile PlayerLevelingConfig config;
    private PlayerLevelingService levelingService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new PlayerLevelingContentLoader(this);
        config = contentLoader.load();
        levelingService = new PlayerLevelingService(() -> config, packs.getPlayerStore());

        Bukkit.getPluginManager().registerEvents(new PlayerLevelingListener(levelingService, packs.getPlayerStore()), this);
        core.getAdminCommandRegistry().register(LevelingAdminCommand.build(this));
    }

    /** Re-reads player-leveling.yml - existing PlayerLevelingService instance keeps working against the same, now-updated config supplier. */
    public void reloadContent() {
        config = contentLoader.load();
    }

    public PlayerLevelingService getLevelingService() {
        return levelingService;
    }
}
