package me.dontshare.yieldcosmetics;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldcosmetics.data.CosmeticProfile;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcosmetics.command.CosmeticsAdminCommand;
import me.dontshare.yieldcosmetics.command.CosmeticsCommand;
import me.dontshare.yieldcosmetics.data.CosmeticContentLoader.Content;
import me.dontshare.yieldcosmetics.data.CosmeticContentLoader;
import me.dontshare.yieldcosmetics.gui.CosmeticsGui;
import me.dontshare.yieldcosmetics.listener.NameplateJoinListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class YieldCosmetics extends JavaPlugin {

    private CosmeticContentLoader contentLoader;
    private volatile Content content;
    private CosmeticService cosmeticService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        contentLoader = new CosmeticContentLoader(this, getLogger());
        content = contentLoader.load();

        NameplateService nameplateService = new NameplateService(core.getScoreboardManager());
        PlayerDataStore<CosmeticProfile> store = PlayerStores.register(
                this, core.getListenerManager(), core.getDatabaseManager(),
                "cosmetics", CosmeticProfile.class, CosmeticProfile::new, "cosmetic data");
        cosmeticService = new CosmeticService(() -> content, store, nameplateService);
        CosmeticPopularityService popularityService = new CosmeticPopularityService(this, () -> content, core.getDatabaseManager());
        popularityService.start();

        // Extends yield-core's single chat renderer rather than registering a
        // competing one - see ChatFormatter's own Javadoc for why.
        core.getChatFormatter().registerMessageStyleProvider(cosmeticService::messageStyleFor);
        core.getChatFormatter().registerNameTagProvider(cosmeticService::nameTagFor);

        core.getListenerManager().register(new NameplateJoinListener(cosmeticService, nameplateService));

        CosmeticsGui cosmeticsGui = new CosmeticsGui(store, cosmeticService, popularityService, core.getGuiManager());

        CommandManager.register(this, CosmeticsCommand.build(cosmeticsGui), "Browse and equip chat colors, nameplates, and tags", List.of());
        core.getAdminCommandRegistry().register(CosmeticsAdminCommand.build(this));
    }

    /** Re-reads cosmetics.yml - existing equipped ids on player profiles that no longer resolve just stop applying (never crash), same tolerant behavior as every other content-reload path in this project. */
    public void reloadContent() {
        content = contentLoader.load();
    }

    public CosmeticService getCosmeticService() {
        return cosmeticService;
    }
}
