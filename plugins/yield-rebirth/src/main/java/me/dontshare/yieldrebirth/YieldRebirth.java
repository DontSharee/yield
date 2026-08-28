package me.dontshare.yieldrebirth;

import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldrebirth.command.RebirthAdminCommand;
import me.dontshare.yieldrebirth.command.RebirthCommand;
import me.dontshare.yieldrebirth.dialog.RebirthDialog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class YieldRebirth extends JavaPlugin {

    private RebirthService rebirthService;

    @Override
    public void onEnable() {
        saveResource("rebirth.yml", false);

        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        rebirthService = new RebirthService(packs.getPlayerStore(), loadRebirthConfig());
        packs.setCoinMultiplierProvider(rebirthService::coinMultiplier);

        RebirthDialog rebirthDialog = new RebirthDialog(rebirthService, packs.getPlayerStore());
        CommandManager.register(this, RebirthCommand.build(rebirthDialog), "Rebirth for a permanent income boost", List.of());
        CommandManager.register(this, RebirthAdminCommand.build(this), "Yield Rebirth admin commands", List.of());
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            // Fall back to the default flat 1.0 multiplier once rebirth is no longer present.
            packs.setCoinMultiplierProvider(null);
        }
    }

    /** Re-reads rebirth.yml's cost curve and re-registers the (possibly changed) multiplier with yield-packs. */
    public void reloadRebirthConfig() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        rebirthService = new RebirthService(packs.getPlayerStore(), loadRebirthConfig());
        packs.setCoinMultiplierProvider(rebirthService::coinMultiplier);
    }

    private YamlConfiguration loadRebirthConfig() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "rebirth.yml"));
    }

    public RebirthService getRebirthService() {
        return rebirthService;
    }
}
