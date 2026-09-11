package me.dontshare.yieldzonemachines;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldrebirth.YieldRebirth;
import me.dontshare.yieldzonemachines.command.ZoneMachinesAdminCommand;
import me.dontshare.yieldzonemachines.data.ZoneMachineContentLoader;
import me.dontshare.yieldzonemachines.data.ZoneMachineContentLoader.ZoneMachineContent;
import me.dontshare.yieldzonemachines.display.WalkInTriggerDisplay;
import me.dontshare.yieldzonemachines.display.ZoneMachineDisplay;
import me.dontshare.yieldzonemachines.gui.CandyApplyGui;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class YieldZoneMachines extends JavaPlugin {

    private ZoneMachineContentLoader contentLoader;
    private volatile ZoneMachineContent content;
    private ZoneMachineService machineService;
    private CandyApplyGui candyApplyGui;
    private ZoneMachineDisplay display;
    private WalkInTriggerDisplay walkInDisplay;
    private YieldPacks packs;
    private YieldZones zones;
    private YieldRebirth rebirth;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        packs = JavaPlugin.getPlugin(YieldPacks.class);
        zones = JavaPlugin.getPlugin(YieldZones.class);
        rebirth = JavaPlugin.getPlugin(YieldRebirth.class);

        contentLoader = new ZoneMachineContentLoader(this, getLogger(), zones::getZones);
        // Safe empty default until the deferred load below actually runs -
        // same reasoning as yield-packstations' own onEnable: yield-zones'
        // own real content isn't populated until ITS deferred load has run,
        // and validating our own "zone:" references synchronously here
        // would check them against still-empty placeholder data on every
        // fresh boot.
        content = new ZoneMachineContent(List.of(), List.of());

        machineService = new ZoneMachineService(packs, zones::getZones, zones.getZoneLockService(),
                rebirth.getRebirthService());
        candyApplyGui = new CandyApplyGui(packs, machineService, core.getGuiManager());
        display = new ZoneMachineDisplay(this, packs, rebirth.getRebirthService(), machineService, candyApplyGui);
        display.start();

        walkInDisplay = new WalkInTriggerDisplay(this, packs, zones::getZones, zones.getZoneLockService());
        walkInDisplay.start();

        core.getAdminCommandRegistry().register(ZoneMachinesAdminCommand.build(this));

        // Deferred for the same reason yield-packstations defers its own
        // load - guarantees yield-zones' own deferred reloadContent has
        // already run earlier in this same tick, since it's a hard
        // dependency and therefore schedules its own next-tick task before
        // we schedule ours.
        Bukkit.getScheduler().runTask(this, this::reloadContent);
    }

    /** Re-reads zone-machines.yml and re-syncs every physical machine's spawned entities/click handlers to the new content - see ZoneMachineDisplay#reload/WalkInTriggerDisplay#reload. */
    public void reloadContent() {
        content = contentLoader.load();
        display.reload(content.machines());
        walkInDisplay.reload(content.walkInTriggers());
    }
}
