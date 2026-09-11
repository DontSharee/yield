package me.dontshare.yieldrebirth;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldrebirth.command.RebirthAdminCommand;
import me.dontshare.yieldrebirth.command.RebirthCommand;
import me.dontshare.yieldrebirth.dialog.RebirthDialog;
import me.dontshare.yieldrebirth.listener.RebirthFanfareListener;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class YieldRebirth extends JavaPlugin {

    private RebirthService rebirthService;
    /** Owned here (not RebirthService) so a registered provider survives {@link #reloadRebirthConfig} replacing rebirthService wholesale. */
    private final Map<String, Function<PackPlayerProfile, Double>> grantMultiplierProviders = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveResource("rebirth.yml", false);

        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        rebirthService = new RebirthService(packs.getPlayerStore(), loadRebirthConfig(), this::grantMultiplier);
        packs.registerCoinMultiplierProvider("rebirth", rebirthService::coinMultiplier);

        RebirthDialog rebirthDialog = new RebirthDialog(rebirthService, packs.getPlayerStore());
        CommandManager.register(this, RebirthCommand.build(rebirthDialog), "Rebirth for a permanent income boost", List.of());
        core.getAdminCommandRegistry().register(RebirthAdminCommand.build(this));
        Bukkit.getPluginManager().registerEvents(new RebirthFanfareListener(), this);
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider("rebirth");
        }
    }

    /** Re-reads rebirth.yml's cost curve and re-registers the (possibly changed) multiplier with yield-packs. */
    public void reloadRebirthConfig() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        rebirthService = new RebirthService(packs.getPlayerStore(), loadRebirthConfig(), this::grantMultiplier);
        packs.registerCoinMultiplierProvider("rebirth", rebirthService::coinMultiplier);
    }

    private YamlConfiguration loadRebirthConfig() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "rebirth.yml"));
    }

    public RebirthService getRebirthService() {
        return rebirthService;
    }

    /** Called by whichever plugin wants to boost how many EFFECTIVE rebirths one actual rebirth grants (e.g. yield-upgrades' rebirth-boost upgrade) - see RebirthService#rebirth. */
    public void registerGrantMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        grantMultiplierProviders.put(key, provider);
    }

    public void unregisterGrantMultiplierProvider(String key) {
        grantMultiplierProviders.remove(key);
    }

    private double grantMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : grantMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }
}
