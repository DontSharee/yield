package me.dontshare.yieldspawnnpcs;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldspawnnpcs.command.SpawnNpcsAdminCommand;
import me.dontshare.yieldspawnnpcs.crate.CrateContentLoader;
import me.dontshare.yieldspawnnpcs.crate.CrateDefinition;
import me.dontshare.yieldspawnnpcs.crate.CrateDisplay;
import me.dontshare.yieldspawnnpcs.crate.CrateKeyDropListener;
import me.dontshare.yieldspawnnpcs.crate.CrateService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Crates ("physically smack a station to spend a Key" - see {@code
 * CrateDisplay}/{@code CrateService}) - the farmed-Key counterpart to
 * yield-lootboxes' own real-money boxes. NOT part of yield-packs' Store hub
 * - that's the Buycraft/Tebex-style real-money storefront, a different
 * concept from this. Every crate's own physical station is global (spawn,
 * not zone-gated) and always visible - no in-game command opens a menu for
 * this anymore, only smacking the real station does anything.
 */
public final class YieldSpawnNpcs extends JavaPlugin {

    private YieldPacks packs;

    private CrateContentLoader crateContentLoader;
    private volatile Map<String, CrateDefinition> crateContent;
    private CrateService crateService;
    private CrateDisplay crateDisplay;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        packs = JavaPlugin.getPlugin(YieldPacks.class);

        crateContentLoader = new CrateContentLoader(this, getLogger());
        crateContent = crateContentLoader.load();
        crateService = new CrateService(() -> crateContent, packs);
        crateDisplay = new CrateDisplay(this, packs, crateService);
        crateDisplay.start();
        crateDisplay.reload(crateContent);

        core.getListenerManager().register(new CrateKeyDropListener(crateService));
        core.getAdminCommandRegistry().register(SpawnNpcsAdminCommand.build(this));
    }

    /** Re-reads crates.yml and rebuilds every crate's physical station (new location/entity ids included). */
    public void reloadContent() {
        crateContent = crateContentLoader.load();
        crateDisplay.reload(crateContent);
    }
}
