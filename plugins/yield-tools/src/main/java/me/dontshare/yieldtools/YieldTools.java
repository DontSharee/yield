package me.dontshare.yieldtools;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldtools.command.ToolsAdminCommand;
import me.dontshare.yieldtools.command.ToolsCommand;
import me.dontshare.yieldtools.data.ToolContentLoader;
import me.dontshare.yieldtools.data.ToolDefinition;
import me.dontshare.yieldtools.data.ToolProfile;
import me.dontshare.yieldtools.gui.ToolsGui;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Hand-held tools: a linear path of items, bought with coins, that make the
 * player's taps hit harder while held. Everything about the tools is
 * tools.yml; this plugin is the path, the menu, and one tap multiplier
 * provider in yield-zones' TapService - which is all a tool actually is.
 */
public final class YieldTools extends JavaPlugin {

    private volatile List<ToolDefinition> tools = List.of();

    @Override
    public void onEnable() {
        YieldCore core = (YieldCore) Bukkit.getPluginManager().getPlugin("yield-core");
        YieldPacks packs = (YieldPacks) Bukkit.getPluginManager().getPlugin("yield-packs");
        YieldZones zones = (YieldZones) Bukkit.getPluginManager().getPlugin("yield-zones");
        if (core == null || packs == null || zones == null || zones.getTapService() == null) {
            getLogger().severe("a hard dependency is missing - disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        reloadContent();

        PlayerDataStore<ToolProfile> store = PlayerStores.register(this, core.getListenerManager(),
                core.getDatabaseManager(), "tools", ToolProfile.class, ToolProfile::new, "tool data");
        ToolItem toolItem = new ToolItem(this);
        ToolService toolService = new ToolService(() -> tools, store, packs, toolItem);

        // The whole of what a tool does: multiply taps while it's held.
        zones.getTapService().registerTapMultiplierProvider("tool", (player, profile) -> toolService.tapMultiplier(player));

        ToolsGui gui = new ToolsGui(toolService, packs, core.getGuiManager(), player -> {
            var profile = packs.getPlayerStore().getCached(player.getUniqueId());
            if (profile == null) {
                return 0L;
            }
            // Bare-handed: the tool's own share is shown separately in the menu.
            double held = toolService.tapMultiplier(player);
            return Math.round(zones.getTapService().tapDamage(player, profile) / held);
        });
        core.getListenerManager().register(new ToolListener(this, toolService, toolItem, gui));
        CommandManager.register(this, ToolsCommand.build(gui), "Unlock tools that make your taps hit harder", List.of());
        core.getAdminCommandRegistry().register(ToolsAdminCommand.build(this, toolService));
    }

    public void reloadContent() {
        tools = List.copyOf(new ToolContentLoader(this, getLogger()).load());
    }
}
