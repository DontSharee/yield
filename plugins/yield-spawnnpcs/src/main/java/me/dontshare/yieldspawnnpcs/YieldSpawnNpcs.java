package me.dontshare.yieldspawnnpcs;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldspawnnpcs.command.CrateCommand;
import me.dontshare.yieldspawnnpcs.command.SpawnNpcsAdminCommand;
import me.dontshare.yieldspawnnpcs.crate.CrateContentLoader;
import me.dontshare.yieldspawnnpcs.crate.CrateDefinition;
import me.dontshare.yieldspawnnpcs.crate.CrateGui;
import me.dontshare.yieldspawnnpcs.crate.CrateService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * The in-game-Credits Crate Shop ("/crates") - the in-game-currency
 * counterpart to yield-lootboxes' own real-money boxes (see {@code
 * CrateService} for why this is a fully separate config/content rather than
 * reusing that plugin's own). NOT part of yield-packs' Store hub - that's
 * the Buycraft/Tebex-style real-money storefront, and this is a Credits
 * purchase, a genuinely different concept (pending a redesign to spend
 * farmed Keys instead of Credits directly - not yet built). NPCs themselves
 * are NOT built here - FancyNpcs (already installed on this server) handles
 * those directly: create an NPC in-game (see FancyNpcs' own "/npc create"
 * command), then attach a right-click "PLAYER_COMMAND" action running
 * "/store", "/ranks", or "/crates" - no custom Java needed for any of the
 * three, since all three are already real, standalone player commands.
 */
public final class YieldSpawnNpcs extends JavaPlugin {

    private YieldPacks packs;

    private CrateContentLoader crateContentLoader;
    private volatile Map<String, CrateDefinition> crateContent;
    private CrateService crateService;
    private CrateGui crateGui;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        packs = JavaPlugin.getPlugin(YieldPacks.class);

        crateContentLoader = new CrateContentLoader(this, getLogger());
        crateContent = crateContentLoader.load();
        crateService = new CrateService(() -> crateContent, packs);
        crateGui = new CrateGui(packs, crateService, core.getGuiManager());

        CommandManager.register(this, CrateCommand.build(crateGui), "Open the Crate Shop", List.of());
        core.getAdminCommandRegistry().register(SpawnNpcsAdminCommand.build(this));
    }

    /** Re-reads crates.yml. */
    public void reloadContent() {
        crateContent = crateContentLoader.load();
    }
}
