package me.dontshare.yieldevents;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldevents.data.EventProfile;
import me.dontshare.yieldevents.data.EventQuest;
import me.dontshare.yieldevents.data.EventShopEntry;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    private final Supplier<Map<String, ZoneDefinition>> zones;

    public EventService(Supplier<List<SeasonalEvent>> events, PlayerDataStore<EventProfile> store,
                         Supplier<Map<String, ZoneDefinition>> zones) {
        this.events = events;
        this.store = store;
        this.zones = zones;
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

    /**
     * The event that owns {@code zoneId}, running or not - null if the zone
     * belongs to no event and is therefore an ordinary zone.
     * <p>
     * Deliberately not filtered by {@link #active()}: a closed event zone is
     * exactly the case the access gate has to recognise, and it can only do
     * that by knowing the zone is seasonal at all.
     */
    public SeasonalEvent eventOwning(String zoneId) {
        if (zoneId == null) {
            return null;
        }
        return events.get().stream()
                .filter(event -> zoneId.equals(event.zoneId()))
                .findFirst()
                .orElse(null);
    }

    /** Whether this player is standing in the event's own zone - false when the event names no zone, in which case everywhere counts. */
    public boolean inEventZone(Player player, SeasonalEvent event) {
        if (event.zoneId() == null) {
            return true;
        }
        ZoneDefinition zone = zones.get().get(event.zoneId());
        return zone != null && zone.region().contains(player.getLocation());
    }

    public long progress(Player player, SeasonalEvent event, EventQuest.Goal goal) {
        return store.getOrCreate(player.getUniqueId()).progress(event.seasonId(), goal);
    }

    /** Counts progress toward every quest watching {@code goal}. Saving is left to the caller's own batch - this is called several times a second. */
    public void addProgress(Player player, SeasonalEvent event, EventQuest.Goal goal, long amount) {
        store.getOrCreate(player.getUniqueId()).addProgress(event.seasonId(), goal, amount);
    }

    public boolean isComplete(Player player, SeasonalEvent event, EventQuest quest) {
        return progress(player, event, quest.goal()) >= quest.target();
    }

    public boolean hasClaimed(Player player, SeasonalEvent event, EventQuest quest) {
        return store.getOrCreate(player.getUniqueId()).hasClaimed(event.seasonId(), quest.id());
    }

    /**
     * Pays a completed quest out, once. Returns false if it was not
     * finished or was already claimed, so the caller can say which.
     * <p>
     * The claim is recorded and SAVED before any reward command runs - a
     * command that fails or a server that dies mid-payout must not leave a
     * quest claimable again, because the alternative is a reward anyone can
     * farm by timing a disconnect.
     */
    public boolean claim(Player player, SeasonalEvent event, EventQuest quest) {
        EventProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.hasClaimed(event.seasonId(), quest.id()) || !isComplete(player, event, quest)) {
            return false;
        }
        profile.markClaimed(event.seasonId(), quest.id());
        if (quest.rewardCandy() > 0) {
            profile.add(event.id(), quest.rewardCandy());
        }
        store.save(player.getUniqueId());
        return true;
    }

    /** What this event's shop has already sold this player, for a limited entry. */
    public int bought(Player player, SeasonalEvent event, EventShopEntry entry) {
        return store.getOrCreate(player.getUniqueId()).bought(event.seasonId(), entry.id());
    }

    /** Why a buy cannot happen, or null when it can. */
    public enum BuyResult { SUCCESS, OUT_OF_STOCK, TOO_POOR }

    /**
     * Buys one of {@code entry}, taking the currency and recording the
     * purchase against the player's stock.
     * <p>
     * Both the deduction and the stock count are written and SAVED before
     * the caller runs the entry's reward commands, for the reason
     * {@link #claim} saves first too: a command that fails, or a server
     * that dies between the two, must not leave a one-per-player pity buy
     * purchasable again. The player has been charged, so the shelf has to
     * agree they bought it.
     */
    public BuyResult buy(Player player, SeasonalEvent event, EventShopEntry entry) {
        EventProfile profile = store.getOrCreate(player.getUniqueId());
        if (entry.remaining(profile.bought(event.seasonId(), entry.id())) <= 0) {
            return BuyResult.OUT_OF_STOCK;
        }
        if (!profile.take(event.id(), entry.price())) {
            return BuyResult.TOO_POOR;
        }
        profile.recordPurchase(event.seasonId(), entry.id(), 1);
        store.save(player.getUniqueId());
        return BuyResult.SUCCESS;
    }

    public void refund(Player player, SeasonalEvent event, long amount) {
        store.getOrCreate(player.getUniqueId()).add(event.id(), amount);
        store.save(player.getUniqueId());
    }

    public PlayerDataStore<EventProfile> getStore() {
        return store;
    }
}
