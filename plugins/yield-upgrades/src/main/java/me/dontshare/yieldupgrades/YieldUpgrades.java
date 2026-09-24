package me.dontshare.yieldupgrades;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldupgrades.data.UpgradeProfile;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldrebirth.YieldRebirth;
import me.dontshare.yieldupgrades.command.UpgradesAdminCommand;
import me.dontshare.yieldupgrades.data.UpgradeContentLoader.UpgradeContent;
import me.dontshare.yieldupgrades.data.UpgradeContentLoader;
import me.dontshare.yieldupgrades.display.UpgradeStationDisplay;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public final class YieldUpgrades extends JavaPlugin implements Listener {

    /** Vanilla's own default walk speed - every PLAYER_SPEED upgrade level adds on top of this, never replaces it. */
    private static final double BASE_WALK_SPEED = 0.2;

    private UpgradeContentLoader contentLoader;
    private volatile UpgradeContent content;
    private UpgradeService upgradeService;
    private UpgradeStationDisplay display;
    private YieldPacks packs;
    private YieldZones zones;
    private YieldRebirth rebirth;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        packs = JavaPlugin.getPlugin(YieldPacks.class);
        zones = JavaPlugin.getPlugin(YieldZones.class);
        rebirth = JavaPlugin.getPlugin(YieldRebirth.class);

        contentLoader = new UpgradeContentLoader(this, getLogger(), zones::getZones);
        // Safe empty default until the deferred load below actually runs -
        // yield-zones' OWN real zone data isn't populated until ITS
        // next-tick-deferred reloadContent has run (see YieldZones#onEnable's
        // own comment on why) - validating our stations' "zone:" references
        // synchronously here would check them against yield-zones' still-empty
        // placeholder map and skip every single station on every fresh boot.
        content = new UpgradeContent(Map.of(), List.of());

        upgradeService = new UpgradeService(() -> content, packs.getPlayerStore(),
                PlayerStores.register(this, core.getListenerManager(), core.getDatabaseManager(),
                        "upgrades", UpgradeProfile.class, UpgradeProfile::new, "upgrade data"), zones::getZones, zones.getZoneLockService());
        display = new UpgradeStationDisplay(this, packs, upgradeService, this::applySpeed);
        display.start();

        packs.registerCoinMultiplierProvider("upgrades", upgradeService::coinMultiplierBonus);
        packs.registerDamageMultiplierProvider("upgrades", upgradeService::damageMultiplierBonus);
        packs.registerAutoSwitchSpeedMultiplierProvider("upgrades", upgradeService::autoSwitchSpeedMultiplier);
        zones.getCubeService().registerDiamondChanceBoostProvider("upgrades", upgradeService::diamondChanceBonus);
        zones.getCubeService().registerFlatDiamondBonusProvider("upgrades", (profile, material) -> upgradeService.flatDiamondBonus(profile));
        zones.getCubeService().registerExtraCubeCapProvider("upgrades", upgradeService::cubeCapBonus);
        zones.getCubeService().registerCubeBonusChanceBoostProvider("upgrades", upgradeService::cubeBonusChanceBonus);
        rebirth.registerGrantMultiplierProvider("upgrades", upgradeService::rebirthGrantMultiplier);
        packs.registerDiamondMultiplierProvider("upgrades", upgradeService::diamondMultiplierBonus);
        packs.registerAttackSpeedMultiplierProvider("upgrades", upgradeService::attackSpeedMultiplier);
        packs.getLuckService().registerExtraLuckProvider("upgrades", upgradeService::hatchLuckBonus);
        zones.getCubeService().registerRespawnDelayMultiplierProvider("upgrades", upgradeService::respawnDelayFactor);
        zones.getPetCombatController().registerCritChanceProvider("upgrades", upgradeService::critChanceBonus);
        zones.getPetCombatController().registerDoubleHitChanceProvider("upgrades", upgradeService::doubleHitBonus);
        zones.getTapService().registerTapMultiplierProvider("upgrades", (player, profile) -> upgradeService.tapMultiplier(profile));
        packs.getEquipmentService().registerBonusEquipSlotsProvider("upgrades", upgradeService::petSlotBonus);
        packs.getPackOpenService().registerCooldownMultiplierProvider("upgrades", upgradeService::hatchCooldownFactor);
        zones.getPetCombatController().registerTripleHitChanceProvider("upgrades", upgradeService::tripleHitBonus);
        zones.getCubeService().registerSpawnWeightMultiplierProvider("upgrades", upgradeService::rareFindWeight);

        Bukkit.getPluginManager().registerEvents(this, this);
        core.getAdminCommandRegistry().register(UpgradesAdminCommand.build(this));

        // Deferred for the same reason yield-zones itself defers its own real
        // load - guarantees yield-zones' deferred reloadContent (which
        // populates real zone data) has already run earlier in this same
        // tick, since yield-zones is a hard dependency and therefore
        // schedules its own next-tick task before we schedule ours.
        Bukkit.getScheduler().runTask(this, this::reloadContent);
    }

    @Override
    public void onDisable() {
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider("upgrades");
            packs.unregisterDamageMultiplierProvider("upgrades");
            packs.unregisterAutoSwitchSpeedMultiplierProvider("upgrades");
            packs.unregisterDiamondMultiplierProvider("upgrades");
            packs.unregisterAttackSpeedMultiplierProvider("upgrades");
            packs.getLuckService().unregisterExtraLuckProvider("upgrades");
            packs.getEquipmentService().unregisterBonusEquipSlotsProvider("upgrades");
            packs.getPackOpenService().unregisterCooldownMultiplierProvider("upgrades");
        }
        if (zones != null) {
            zones.getCubeService().unregisterDiamondChanceBoostProvider("upgrades");
            zones.getCubeService().unregisterFlatDiamondBonusProvider("upgrades");
            zones.getCubeService().unregisterExtraCubeCapProvider("upgrades");
            zones.getCubeService().unregisterCubeBonusChanceBoostProvider("upgrades");
            zones.getCubeService().unregisterRespawnDelayMultiplierProvider("upgrades");
            zones.getPetCombatController().unregisterCritChanceProvider("upgrades");
            zones.getPetCombatController().unregisterDoubleHitChanceProvider("upgrades");
            zones.getTapService().unregisterTapMultiplierProvider("upgrades");
            zones.getPetCombatController().unregisterTripleHitChanceProvider("upgrades");
            zones.getCubeService().unregisterSpawnWeightMultiplierProvider("upgrades");
        }
        if (rebirth != null) {
            rebirth.unregisterGrantMultiplierProvider("upgrades");
        }
    }

    /** Re-reads upgrades.yml and re-syncs every physical station's spawned entities/click handlers to the new content - see UpgradeStationDisplay#reload. */
    public void reloadContent() {
        content = contentLoader.load();
        display.reload(content.stations());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applySpeed(event.getPlayer());
    }

    /** Vanilla base walk speed plus every PLAYER_SPEED upgrade's bonus - called on join and right after any successful upgrade (cheap/idempotent even when the upgrade purchased wasn't a speed one). */
    private void applySpeed(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        float speed = (float) Math.max(-1.0, Math.min(1.0, BASE_WALK_SPEED + upgradeService.speedBonus(profile)));
        player.setWalkSpeed(speed);
    }

    public UpgradeService getUpgradeService() {
        return upgradeService;
    }
}
