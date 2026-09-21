package me.dontshare.yieldevents;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.command.EventCommand;
import me.dontshare.yieldevents.command.EventsAdminCommand;
import me.dontshare.yieldevents.data.EventContentLoader;
import me.dontshare.yieldevents.data.EventProfile;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldevents.listener.EventCurrencyListener;
import me.dontshare.yieldpackstations.YieldPackStations;
import me.dontshare.yieldpackstations.data.PackStationContentLoader;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Seasonal events: a window of dates in which an extra currency drops from
 * cubes and an extra egg stands at spawn selling pets that exist nowhere
 * else.
 * <p>
 * Almost nothing here is new machinery. The egg is an ordinary pack in
 * packs.yml, so it arrives with the hatch animation, the odds screens, the
 * Index and the Huge chase already attached; its station is an ordinary
 * station in pack-stations.yml, so it arrives with the big dragon egg, the
 * smack-to-hatch, the bulk rungs and the auto-hatch loop. This plugin owns
 * only the three genuinely seasonal parts: WHEN, what the currency is, and
 * what it buys.
 * <p>
 * The event's own start and end need no admin action and no restart - the
 * date decides, re-read live, so a server that is up at midnight on the
 * 31st starts the event by itself.
 */
public final class YieldEvents extends JavaPlugin {

    /** How often the active event is re-checked for a start/end announcement - a minute is plenty for a thing that changes at midnight. */
    private static final long WATCH_INTERVAL_TICKS = 20L * 60;

    private List<SeasonalEvent> events = List.of();
    private EventService eventService;
    private String announcedEventId;

    @Override
    public void onEnable() {
        YieldCore core = (YieldCore) Bukkit.getPluginManager().getPlugin("yield-core");
        YieldPackStations stations = (YieldPackStations) Bukkit.getPluginManager().getPlugin("yield-packstations");
        if (core == null || stations == null) {
            getLogger().severe("yield-core/yield-packstations missing - disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        PlayerDataStore<EventProfile> store = PlayerStores.register(this, core.getListenerManager(),
                core.getDatabaseManager(), "events", EventProfile.class, EventProfile::new, "event data");
        eventService = new EventService(() -> events, store);
        reloadContent();

        // The station's contents ARE the active event's egg, asked for live -
        // so it fills itself the moment an event starts and empties itself
        // the moment one ends, with no reload and no placement change.
        stations.getStationService().registerDynamicPack(PackStationContentLoader.EVENT_KEY,
                eventService::activeEggId, this::stationLabel);
        for (SeasonalEvent event : events) {
            stations.getStationService().registerAlternateCharge(event.eggId(), new EventStationCharge(eventService));
        }

        core.getListenerManager().register(new EventCurrencyListener(eventService));
        new EventBossBarService(this, eventService).start();
        CommandManager.register(this, EventCommand.build(eventService), "See what event is running", List.of());
        core.getAdminCommandRegistry().register(EventsAdminCommand.build(this));
        Bukkit.getScheduler().runTaskTimer(this, this::watchForChange, WATCH_INTERVAL_TICKS, WATCH_INTERVAL_TICKS);
    }

    /** Re-reads events.yml. Registered charges are per egg id and survive it, since the eggs themselves are config in another plugin. */
    public void reloadContent() {
        events = new EventContentLoader(this, getLogger()).load();
    }

    private String stationLabel() {
        SeasonalEvent event = eventService.active();
        return event == null ? "" : "<" + event.color() + "><bold>" + event.displayName() + "</bold></" + event.color() + ">";
    }

    /**
     * Announces an event starting or ending. Watched rather than scheduled
     * because a server is not guaranteed to be running at the moment a date
     * rolls over - it can boot into the middle of an event, or come back up
     * after one ended - and a watcher gets both right without caring which
     * happened.
     */
    private void watchForChange() {
        SeasonalEvent active = eventService.active();
        String activeId = active != null ? active.id() : null;
        if (java.util.Objects.equals(activeId, announcedEventId)) {
            return;
        }
        if (active != null) {
            Bukkit.broadcast(Text.parse(
                    "<" + active.color() + "><bold><name> has begun!</bold></" + active.color() + ">"
                            + " <gray>Break cubes for <currency>, then hatch the event egg at spawn.</gray>",
                    Placeholder.unparsed("name", active.displayName()),
                    Placeholder.unparsed("currency", active.currencyName())));
        } else if (announcedEventId != null) {
            Bukkit.broadcast(Text.parse("<gray>The event has ended. Its pets are gone until it comes round again.</gray>"));
        }
        announcedEventId = activeId;
    }
}
