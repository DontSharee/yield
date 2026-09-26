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

        me.dontshare.yieldcore.admin.PlayerEdits.register(new me.dontshare.yieldcore.admin.PlayerEdits.Stat<>(
                "level", "Level", "Progress", me.dontshare.yieldcore.admin.PlayerEdits.Kind.NUMBER, 1, config.maxLevel(),
                "Sets the level with no XP into it.", store,
                profile -> String.valueOf(profile.getLevel()),
                (profile, value) -> {
                    String before = String.valueOf(profile.getLevel());
                    profile.setLevel((int) me.dontshare.yieldcore.admin.PlayerEdits.number(value, 1, config.maxLevel()));
                    profile.setXp(0);
                    return me.dontshare.yieldcore.admin.PlayerEdits.restore("level", before);
                }, java.util.List::of, levelingService::syncBar, this));
    }

    @Override
    public void onDisable() {
        me.dontshare.yieldcore.admin.PlayerEdits.unregisterAll(this);
    }

    /** Re-reads player-leveling.yml - existing PlayerLevelingService instance keeps working against the same, now-updated config supplier. */
    public void reloadContent() {
        config = contentLoader.load();
    }

    public PlayerLevelingService getLevelingService() {
        return levelingService;
    }
}
