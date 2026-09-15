package me.dontshare.yieldleveling;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldleveling.data.LevelingProfile;
import me.dontshare.yieldleveling.command.LevelingAdminCommand;
import me.dontshare.yieldleveling.data.PlayerLevelingConfig;
import me.dontshare.yieldleveling.data.PlayerLevelingContentLoader;
import me.dontshare.yieldleveling.listener.PlayerLevelingListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class YieldLeveling extends JavaPlugin {

    private PlayerLevelingContentLoader contentLoader;
    private volatile PlayerLevelingConfig config;
    private PlayerLevelingService levelingService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        contentLoader = new PlayerLevelingContentLoader(this);
        config = contentLoader.load();

        PlayerDataStore<LevelingProfile> store = PlayerStores.register(
                this, core.getListenerManager(), core.getDatabaseManager(),
                "leveling", LevelingProfile.class, LevelingProfile::new, "leveling data");
        levelingService = new PlayerLevelingService(() -> config, store);

        Bukkit.getPluginManager().registerEvents(new PlayerLevelingListener(levelingService), this);
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
