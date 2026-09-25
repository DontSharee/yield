package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldanalytics.data.AnalyticsProfile;
import me.dontshare.yieldanalytics.webhook.WebhookService;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.player.PlayerProfile;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import me.dontshare.yieldzones.event.ZoneUnlockedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions, playtime, first visits and milestones - everything per player
 * that needs watching as it happens rather than reading back later.
 * <p>
 * Playtime is added once a minute as well as on quit, so a crash loses at
 * most a minute of it. The tutorial is watched by polling the step on the
 * player's core profile, since the tutorial fires no events of its own.
 */
public final class SessionTracker implements Listener {

    /** Upper bounds, in minutes, of the session-length histogram's buckets; the last is open-ended. */
    public static final int[] SESSION_BUCKETS = {5, 15, 30, 60, 120, 240};

    private final PlayerDataStore<AnalyticsProfile> store;
    private final PlayerDataStore<PlayerProfile> coreStore;
    private final ActivityStore activity;
    private final DatabaseManager database;
    private final TutorialSteps tutorial;
    private final YieldZones zones;
    private final WebhookService webhooks;
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private final Map<UUID, Long> accountedUntil = new ConcurrentHashMap<>();
    private final Set<UUID> newSinceMark = ConcurrentHashMap.newKeySet();

    public SessionTracker(PlayerDataStore<AnalyticsProfile> store, PlayerDataStore<PlayerProfile> coreStore,
                          ActivityStore activity, DatabaseManager database, TutorialSteps tutorial,
                          YieldZones zones, WebhookService webhooks) {
        this.store = store;
        this.coreStore = coreStore;
        this.activity = activity;
        this.database = database;
        this.tutorial = tutorial;
        this.zones = zones;
        this.webhooks = webhooks;
    }

    public long sessionStartOf(UUID playerId) {
        return sessionStart.getOrDefault(playerId, 0L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (Exclusions.excluded(id)) {
            return;
        }
        long now = System.currentTimeMillis();
        AnalyticsProfile profile = store.getOrCreate(id);
        if (profile.getFirstSeen() == 0) {
            PlayerProfile core = coreStore.getCached(id);
            long coreFirst = core != null ? core.getFirstJoined() : 0;
            // New if core only just met them too - otherwise they played
            // before analytics were installed, and are only new to us.
            boolean brandNew = coreFirst == 0 || now - coreFirst < 5 * 60_000L;
            profile.setFirstSeen(brandNew || coreFirst == 0 ? now : coreFirst);
            if (brandNew) {
                activity.count("newPlayers");
                newSinceMark.add(id);
                webhooks.newPlayer(player.getName());
                if (core != null && !core.isTutorialSkipped() && core.getTutorialStep() < Math.max(1, tutorial.count())) {
                    profile.setTutorialStartedAt(now);
                    activity.count("tutorialStarted");
                }
            }
        }
        profile.setSessions(profile.getSessions() + 1);
        profile.setLastSeen(now);
        sessionStart.put(id, now);
        accountedUntil.put(id, now);
        activity.count("joins");
        store.save(id);
    }

    /** LOWEST: the analytics record is still loaded, and its quit save comes after. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long started = sessionStart.remove(id);
        if (started == null) {
            accountedUntil.remove(id);
            return;
        }
        long now = System.currentTimeMillis();
        AnalyticsProfile profile = store.getCached(id);
        account(id, profile, now);
        accountedUntil.remove(id);
        long length = now - started;
        if (profile != null) {
            profile.setLongestSessionMs(Math.max(profile.getLongestSessionMs(), length));
            profile.setLastSeen(now);
        }
        activity.count("sessionsEnded");
        activity.count("sessionMs", length);
        activity.count("sessionLen_" + bucketOf(length));
    }

    public static String bucketOf(long lengthMs) {
        long minutes = lengthMs / 60_000L;
        for (int bound : SESSION_BUCKETS) {
            if (minutes < bound) {
                return "lt" + bound;
            }
        }
        return "ge" + SESSION_BUCKETS[SESSION_BUCKETS.length - 1];
    }

    private void account(UUID id, AnalyticsProfile profile, long now) {
        Long since = accountedUntil.get(id);
        if (since == null || profile == null) {
            return;
        }
        long delta = Math.max(0, now - since);
        profile.setPlaytimeMs(profile.getPlaytimeMs() + delta);
        accountedUntil.put(id, now);
        activity.count("playtimeMs", delta);
    }

    /** Once a minute, main thread: playtime so far, and today's active players. */
    public void minuteTick() {
        long now = System.currentTimeMillis();
        List<UUID> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (Exclusions.excluded(id)) {
                continue;
            }
            online.add(id);
            account(id, store.getCached(id), now);
        }
        List<UUID> fresh = List.copyOf(newSinceMark);
        newSinceMark.removeAll(fresh);
        int onlineCount = online.size();
        database.supplyAsync(() -> {
            activity.markActive(online, fresh, onlineCount);
            return null;
        });
    }

    /** Every couple of seconds, main thread: tutorial progress, which fires no events to listen for. */
    public void watchTutorial() {
        int steps = tutorial.count();
        if (steps == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (Exclusions.excluded(id)) {
                continue;
            }
            PlayerProfile core = coreStore.getCached(id);
            AnalyticsProfile profile = store.getCached(id);
            if (core == null || profile == null || !profile.getTutorialOutcome().isEmpty()) {
                continue;
            }
            int step = Math.min(core.getTutorialStep(), steps);
            for (int reached = profile.getTutorialStepReached() + 1; reached <= step; reached++) {
                activity.count("tutorialStep_" + reached);
            }
            if (step > profile.getTutorialStepReached()) {
                profile.setTutorialStepReached(step);
            }
            if (core.isTutorialSkipped()) {
                profile.setTutorialOutcome("skipped");
                profile.setTutorialFinishedAt(now);
                activity.count("tutorialSkipped");
            } else if (step >= steps) {
                profile.setTutorialOutcome("completed");
                profile.setTutorialFinishedAt(now);
                activity.count("tutorialCompleted");
                if (profile.getTutorialStartedAt() > 0) {
                    activity.count("tutorialCompletedMs", now - profile.getTutorialStartedAt());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZoneEntered(ZoneEnteredEvent event) {
        reached(event.getPlayer(), event.getZone(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZoneUnlocked(ZoneUnlockedEvent event) {
        reached(event.getPlayer(), event.getZone(), true);
    }

    private void reached(Player player, ZoneDefinition zone, boolean unlocked) {
        if (Exclusions.excluded(player.getUniqueId())) {
            return;
        }
        if (unlocked) {
            activity.count("zoneUnlocks");
            activity.count("zoneUnlock_" + zone.id());
        }
        AnalyticsProfile profile = store.getCached(player.getUniqueId());
        if (profile != null && profile.getZoneReachedAt().putIfAbsent(zone.id(), System.currentTimeMillis()) == null) {
            activity.count("zoneReached_" + zone.id());
        }
    }

    /** Server stopping: bank the playtime of everyone still on. */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (UUID id : List.copyOf(accountedUntil.keySet())) {
            account(id, store.getCached(id), now);
        }
    }

    public YieldZones zones() {
        return zones;
    }
}
