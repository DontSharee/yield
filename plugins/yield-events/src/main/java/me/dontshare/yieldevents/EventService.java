package me.dontshare.yieldevents;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldevents.data.EventProfile;
import me.dontshare.yieldevents.data.SeasonalEvent;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Which event is running, and what a player has earned from it.
 * <p>
 * "Running" is decided by the server's own date, re-read on every call
 * rather than latched at startup: a server that stays up across midnight on
 * the 31st should start the event without anyone restarting it, and one
 * that boots mid-event should already be in it.
 * <p>
 * Only one event runs at a time. Overlapping windows are a config mistake
 * rather than a feature, and the earliest-starting one wins so the outcome
 * is at least predictable.
 */
public final class EventService {

    private final Supplier<List<SeasonalEvent>> events;
    private final PlayerDataStore<EventProfile> store;

    public EventService(Supplier<List<SeasonalEvent>> events, PlayerDataStore<EventProfile> store) {
        this.events = events;
        this.store = store;
    }

    /** The event running right now, or null. */
    public SeasonalEvent active() {
        LocalDate today = LocalDate.now();
        return events.get().stream()
                .filter(event -> event.isActiveOn(today))
                .min((a, b) -> a.start().compareTo(b.start()))
                .orElse(null);
    }

    /** The egg the event station should be offering right now - null when nothing is running, which the station renders as "nothing in it". */
    public String activeEggId() {
        SeasonalEvent event = active();
        return event != null ? event.eggId() : null;
    }

    public long balance(Player player, SeasonalEvent event) {
        return store.getOrCreate(player.getUniqueId()).balance(event.id());
    }

    public void grant(Player player, SeasonalEvent event, long amount) {
        store.getOrCreate(player.getUniqueId()).add(event.id(), amount);
    }

    /** Takes {@code amount} of the event's currency if the player has it, saving immediately - money must not be left only in memory. */
    public boolean take(Player player, SeasonalEvent event, long amount) {
        EventProfile profile = store.getOrCreate(player.getUniqueId());
        if (!profile.take(event.id(), amount)) {
            return false;
        }
        store.save(player.getUniqueId());
        return true;
    }

    public void refund(Player player, SeasonalEvent event, long amount) {
        store.getOrCreate(player.getUniqueId()).add(event.id(), amount);
        store.save(player.getUniqueId());
    }

    public PlayerDataStore<EventProfile> getStore() {
        return store;
    }
}
