package me.dontshare.yieldpackstations;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpackstations.command.PackStationsAdminCommand;
import me.dontshare.yieldpackstations.data.BlackMarketConfig;
import me.dontshare.yieldpackstations.data.PackStation;
import me.dontshare.yieldpackstations.data.PackStationContentLoader;
import me.dontshare.yieldpackstations.data.PackStationContentLoader.PackStationContent;
import me.dontshare.yieldpackstations.data.PackStationContentLoader;
import me.dontshare.yieldpackstations.display.PackStationDisplay;
import me.dontshare.yieldpackstations.market.BlackMarketRotationService;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class YieldPackStations extends JavaPlugin {

    private PackStationContentLoader contentLoader;
    private volatile PackStationContent content;
    private BlackMarketRotationService blackMarket;
    private PackStationService stationService;
    private PackStationDisplay display;
    private YieldPacks packs;
    private YieldZones zones;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        packs = JavaPlugin.getPlugin(YieldPacks.class);
        zones = JavaPlugin.getPlugin(YieldZones.class);

        contentLoader = new PackStationContentLoader(this, getLogger(), zones::getZones, packs::getPackRegistry);
        // Safe empty default until the deferred load below actually runs -
        // same reasoning as yield-upgrades' own onEnable: yield-zones'/
        // yield-packs' own real content isn't populated until THEIR
        // deferred loads have run, and validating our own "zone:"/"pack:"
        // references synchronously here would check them against still-
        // empty placeholder data on every fresh boot.
        content = new PackStationContent(List.of(), List.of(), List.of(),
                new BlackMarketConfig(60 * 60 * 1000L, List.of()));

        blackMarket = new BlackMarketRotationService(() -> content);
        stationService = new PackStationService(packs, zones::getZones, zones.getZoneLockService());
        stationService.registerDynamicPack(PackStationContentLoader.BLACK_MARKET_KEY,
                blackMarket::currentPackId, () -> "<#4BD9FF><bold>Black Market</bold></#4BD9FF>");
        display = new PackStationDisplay(this, packs, stationService);
        display.start();
        // Auto-hatch only runs while a player is stood at an egg, and only
        // this plugin knows where the eggs physically are - yield-packs
        // asks rather than looks, since the dependency runs the other way.
        packs.getPackOpenService().registerHatchSiteProvider("pack_stations", display::hatchSiteFor, display::hatchSiteLocation);

        core.getAdminCommandRegistry().register(PackStationsAdminCommand.build(this));

        // Deferred for the same reason yield-upgrades defers its own load -
        // guarantees yield-zones'/yield-packs' own deferred loads have
        // already run earlier in this same tick, since both are hard
        // dependencies and therefore schedule their own next-tick tasks
        // before we schedule ours.
        Bukkit.getScheduler().runTask(this, this::reloadContent);
    }

    /** The station registry other plugins hand their own station contents to - see PackStationService#registerDynamicPack. */
    public PackStationService getStationService() {
        return stationService;
    }

    /** Re-reads pack-stations.yml and re-syncs every physical station's spawned entities/click handlers to the new content - see PackStationDisplay#reload. */
    public void reloadContent() {
        content = contentLoader.load();
        List<PackStation> all = new ArrayList<>(content.zoneStations());
        all.addAll(content.blackMarketStations());
        all.addAll(content.eventStations());
        display.reload(all);
    }
}
