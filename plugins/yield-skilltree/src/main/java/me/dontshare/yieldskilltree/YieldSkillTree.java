package me.dontshare.yieldskilltree;

import me.dontshare.yieldcore.config.BundledConfig;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldskilltree.data.SkillTreeProfile;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldskilltree.command.PrestigeCommand;
import me.dontshare.yieldskilltree.command.SkillTreeAdminCommand;
import me.dontshare.yieldskilltree.command.SkillTreeCommand;
import me.dontshare.yieldskilltree.data.NodeType;
import me.dontshare.yieldskilltree.data.SkillTree;
import me.dontshare.yieldskilltree.data.SkillTreeContentLoader;
import me.dontshare.yieldskilltree.dialog.PrestigeDialog;
import me.dontshare.yieldskilltree.gui.SkillTreeGui;
import me.dontshare.yieldskilltree.listener.SkillFanfareListener;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class YieldSkillTree extends JavaPlugin {

    private static final String PROVIDER_KEY = "skilltree";

    private SkillTreeContentLoader contentLoader;
    private volatile Map<String, SkillTree> trees;
    private SkillTreeService skillTreeService;
    private PrestigeService prestigeService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new SkillTreeContentLoader(this, getLogger());
        trees = contentLoader.load();
        skillTreeService = new SkillTreeService(() -> trees, packs.getPlayerStore(),
                PlayerStores.register(this, core.getListenerManager(), core.getDatabaseManager(),
                        "skilltree", SkillTreeProfile.class, SkillTreeProfile::new, "skill tree data"));

        BundledConfig.sync(this, "prestige.yml");
        prestigeService = new PrestigeService(packs.getPlayerStore(), loadPrestigeConfig());

        registerProviders(packs);

        PrestigeDialog prestigeDialog = new PrestigeDialog(prestigeService, packs.getPlayerStore());
        SkillTreeGui skillTreeGui = new SkillTreeGui(packs.getPlayerStore(), () -> trees, skillTreeService, core.getGuiManager());

        CommandManager.register(this, PrestigeCommand.build(prestigeDialog),
                "Reset Coins/Rebirths for Prestige Points", List.of());
        CommandManager.register(this, SkillTreeCommand.build("upgrades", "upgrades", skillTreeGui),
                "Open the Coins-funded Upgrades skill tree", List.of());
        CommandManager.register(this, SkillTreeCommand.build("prestigeupgrades", "prestige_upgrades", skillTreeGui),
                "Open the Prestige Points-funded Prestige Upgrades skill tree", List.of());
        core.getAdminCommandRegistry().register(SkillTreeAdminCommand.build(this));
        Bukkit.getPluginManager().registerEvents(new SkillFanfareListener(), this);
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterDamageMultiplierProvider(PROVIDER_KEY);
            packs.getLuckService().unregisterExtraLuckProvider(PROVIDER_KEY);
            packs.getEquipmentService().unregisterBonusEquipSlotsProvider(PROVIDER_KEY);
            packs.getPackOpenService().unregisterCooldownMultiplierProvider(PROVIDER_KEY);
        }
    }

    /** Re-reads both tree yamls and prestige.yml, and clears the formula cache - existing PrestigeDialog/SkillTreeGui instances keep working against the same, now-updated PrestigeService/trees. */
    public void reloadContent() {
        trees = contentLoader.load();
        skillTreeService.clearCache();
        prestigeService.reload(loadPrestigeConfig());
    }

    private void registerProviders(YieldPacks packs) {
        packs.registerCoinMultiplierProvider(PROVIDER_KEY,
                profile -> 1.0 + skillTreeService.totalFor(profile, NodeType.COIN_MULTIPLIER));
        packs.registerDamageMultiplierProvider(PROVIDER_KEY,
                profile -> 1.0 + skillTreeService.totalFor(profile, NodeType.DAMAGE_MULTIPLIER));
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY,
                profile -> skillTreeService.totalFor(profile, NodeType.LUCK_MULTIPLIER));
        packs.getEquipmentService().registerBonusEquipSlotsProvider(PROVIDER_KEY,
                (PackPlayerProfile profile) -> (int) Math.round(skillTreeService.totalFor(profile, NodeType.EQUIP_SLOTS)));
        packs.getPackOpenService().registerCooldownMultiplierProvider(PROVIDER_KEY,
                profile -> 1.0 + skillTreeService.totalFor(profile, NodeType.ROLL_SPEED_MULTIPLIER));
    }

    private YamlConfiguration loadPrestigeConfig() {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), "prestige.yml"));
    }
}
