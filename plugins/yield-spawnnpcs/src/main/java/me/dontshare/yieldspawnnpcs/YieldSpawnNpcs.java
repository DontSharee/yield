package me.dontshare.yieldspawnnpcs;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.store.StoreCategory;
import me.dontshare.yieldspawnnpcs.command.CrateCommand;
import me.dontshare.yieldspawnnpcs.command.SpawnNpcsAdminCommand;
import me.dontshare.yieldspawnnpcs.crate.CrateContentLoader;
import me.dontshare.yieldspawnnpcs.crate.CrateDefinition;
import me.dontshare.yieldspawnnpcs.crate.CrateGui;
import me.dontshare.yieldspawnnpcs.crate.CrateService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * The in-game-Credits Crate Shop ("/crates", also reachable as a category
 * button inside yield-packs' shared Store hub - see {@code
 * YieldPacks#registerStoreCategory}) - the in-game-currency counterpart to
 * yield-lootboxes' own real-money boxes (see {@code CrateService} for why
 * this is a fully separate config/content rather than reusing that
 * plugin's own). NPCs themselves are NOT built here - FancyNpcs (already
 * installed on this server) handles those directly: create an NPC in-game
 * (see FancyNpcs' own "/npc create" command), then attach a right-click
 * "PLAYER_COMMAND" action running "/store", "/ranks", or "/crates" - no
 * custom Java needed for any of the three, since all three are already
 * real, standalone player commands.
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
        packs.registerStoreCategory(new StoreCategory("crates", 20, YieldSpawnNpcs::crateCategoryIcon, crateGui::open));
        core.getAdminCommandRegistry().register(SpawnNpcsAdminCommand.build(this));
    }

    /** Re-reads crates.yml. */
    public void reloadContent() {
        crateContent = crateContentLoader.load();
    }

    private static ItemStack crateCategoryIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.ENDER_CHEST).name(MenuLore.buttonName("<#FF7F50>", "CRATES"));
        MenuLore.button("store", List.of(" &7Spend Credits on crates -", " &7coins, diamonds, and rare pets."),
                "<#FF7F50>", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
