package me.dontshare.yieldloadtest;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.status.ServerHealth;
import me.dontshare.yieldcore.status.SyntheticPlayers;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.cube.OreCube;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Spawns, drives and measures the bots.
 * <p>
 * A bot logs in the way a person does - pre-login first, so every plugin's
 * data store loads it, then a real join - gets a squad of random pets with
 * auto-attack on, and stands in a zone fighting. Some share of them walk
 * about and some tap, so the moving-squad and tap paths carry load too.
 * <p>
 * Bot ids come from a fixed name sequence, so {@link #cleanupData} can find
 * and delete every document a bot ever wrote.
 */
final class LoadTestService {

    static final String NAME_PREFIX = "LT_Bot";
    /** Highest bot number {@link #cleanupData} sweeps. */
    static final int MAX_BOTS = 1000;
    private static final int SPAWNS_PER_TICK = 4;
    private static final int WANDER_INTERVAL_TICKS = 60;
    private static final int TAP_INTERVAL_TICKS = 4;
    private static final int LOOK_INTERVAL_TICKS = 10;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final YieldZones zones;
    private final NmsBridge nms;
    private final Map<UUID, NmsBridge.Handle> bots = new LinkedHashMap<>();
    private final Map<UUID, String> zoneOfBot = new LinkedHashMap<>();
    private final List<Runnable> spawnQueue = new ArrayList<>();
    private double wanderShare = 0.2;
    private double tapShare = 0.3;
    private boolean tickPlayers = true;
    private long tickCount;

    /** Per-second deltas of the traffic counters, for the last minute. */
    private final long[][] trafficWindow = new long[PerfTracker.WINDOW_SECONDS][3];
    private int trafficSlot;
    private final long[] lastTraffic = new long[3];

    LoadTestService(JavaPlugin plugin, YieldPacks packs, YieldZones zones) {
        this.plugin = plugin;
        this.packs = packs;
        this.zones = zones;
        this.nms = NmsBridge.create();
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, PerfTracker.timed("loadtest.bots", this::tick), 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sampleTraffic, 20L, 20L);
    }

    int count() {
        return bots.size();
    }

    int queued() {
        return spawnQueue.size();
    }

    void setWanderShare(double share) {
        this.wanderShare = clamp(share);
    }

    void setTapShare(double share) {
        this.tapShare = clamp(share);
    }

    double wanderShare() {
        return wanderShare;
    }

    double tapShare() {
        return tapShare;
    }

    private static double clamp(double share) {
        return Math.max(0, Math.min(1, share));
    }

    static UUID idFor(int number) {
        String name = nameFor(number);
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    static String nameFor(int number) {
        return String.format(Locale.ROOT, "%s%04d", NAME_PREFIX, number);
    }

    /**
     * Brings the bot count up to {@code target}. {@code zoneId} null spreads
     * them over every zone; otherwise they all go to that one - the crowded
     * zone is the case worth testing.
     */
    void scaleTo(int target, String zoneId) {
        target = Math.min(target, MAX_BOTS);
        List<ZoneDefinition> zoneList = new ArrayList<>(zones.getZones().values());
        if (zoneList.isEmpty()) {
            plugin.getLogger().warning("No zones loaded - nowhere to put bots.");
            return;
        }
        int number = 1;
        int toAdd = target - bots.size() - spawnQueue.size();
        int added = 0;
        while (added < toAdd && number <= MAX_BOTS) {
            UUID id = idFor(number);
            if (!bots.containsKey(id) && !zoneOfBot.containsKey(id)) {
                ZoneDefinition zone = zoneId != null ? zones.getZones().get(zoneId)
                        : zoneList.get(added % zoneList.size());
                if (zone == null) {
                    plugin.getLogger().warning("Unknown zone " + zoneId);
                    return;
                }
                int botNumber = number;
                zoneOfBot.put(id, zone.id());
                spawnQueue.add(() -> login(botNumber, zone));
                added++;
            }
            number++;
        }
    }

    /** Removes bots down to {@code target}, newest first. */
    void removeDownTo(int target) {
        spawnQueue.clear();
        List<UUID> ids = new ArrayList<>(bots.keySet());
        for (int i = ids.size() - 1; i >= Math.max(0, target); i--) {
            remove(ids.get(i));
        }
        zoneOfBot.keySet().retainAll(bots.keySet());
    }

    void removeAll() {
        removeDownTo(0);
    }

    @SuppressWarnings("removal") // the only public constructor that needs no live connection
    private void login(int number, ZoneDefinition zone) {
        UUID id = idFor(number);
        String name = nameFor(number);
        SyntheticPlayers.add(id);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                InetAddress address = InetAddress.getLoopbackAddress();
                AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, address, id, false,
                        Bukkit.createProfile(id, name));
                Bukkit.getPluginManager().callEvent(event);
                if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    plugin.getLogger().warning(name + " was refused at pre-login: " + event.getLoginResult());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        zoneOfBot.remove(id);
                        SyntheticPlayers.remove(id);
                    });
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> join(id, name, number, zone));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Pre-login failed for " + name, e);
            }
        });
    }

    private void join(UUID id, String name, int number, ZoneDefinition zone) {
        if (!zoneOfBot.containsKey(id)) {
            return; // removed while logging in
        }
        EmbeddedChannel channel = new EmbeddedChannel(new TrafficSink());
        try {
            // Registered before the join so everything sent during it -
            // sidebar, pets, the zone - reaches the sink like it would a client.
            PacketEvents.getAPI().getProtocolManager().setUser(channel,
                    new User(channel, ConnectionState.PLAY, ClientVersion.getLatest(), new UserProfile(id, name)));
            PacketEvents.getAPI().getProtocolManager().setChannel(id, channel);
            NmsBridge.Handle handle = nms.join(id, name, zone.region().world(), channel, 40000 + number);
            bots.put(id, handle);
            setUp(handle.player(), zone);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not log " + name + " in", e);
            zoneOfBot.remove(id);
            SyntheticPlayers.remove(id);
            PacketEvents.getAPI().getProtocolManager().removeUser(channel);
            channel.close();
        }
    }

    /** Gives a fresh bot something to do: pets, auto-attack, and a spot in its zone. */
    private void setUp(Player bot, ZoneDefinition zone) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(bot.getUniqueId());
        if (profile.getPets().size() < 8) {
            // Pets a player in this zone would really have: the zone's place
            // in the progression picks the same band of the pet list, weakest
            // to strongest. Random pets from the whole game killed a first-zone
            // cube in one hit, and a squad whose target dies every second is
            // not what a crowd there actually costs.
            List<ItemDefinition> pool = packs.getItemRegistry().all().stream()
                    .filter(item -> item.damage() > 0 && !item.huge())
                    .sorted(java.util.Comparator.comparingDouble(ItemDefinition::damage))
                    .toList();
            List<String> zoneOrder = new ArrayList<>(zones.getZones().keySet());
            double position = (zoneOrder.indexOf(zone.id()) + 0.5) / Math.max(1, zoneOrder.size());
            int centre = (int) (position * pool.size());
            int band = Math.max(3, pool.size() / (zoneOrder.size() * 2));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int count = 8 + random.nextInt(8);
            for (int i = 0; i < count && !pool.isEmpty(); i++) {
                int index = Math.max(0, Math.min(pool.size() - 1, centre + random.nextInt(-band, band + 1)));
                profile.addOwnedItem("loadtest", pool.get(index).id());
            }
        }
        for (ZoneDefinition each : zones.getZones().values()) {
            profile.getUnlockedZoneIds().add(each.id());
        }
        if (profile.getCoins().signum() == 0) {
            profile.setCoins(BigInteger.valueOf(1_000_000));
        }
        profile.applyAutoAttack(true);
        packs.getEquipmentService().equipBest(profile);
        packs.getPlayerStore().save(bot.getUniqueId());
        packs.getPetDisplayService().refresh(bot);
        // A bot is ticked server-side with no client holding it up, and a
        // test world has no floor under the zones - it would fall straight
        // out of its zone. Holding its height is what standing on a real
        // floor looks like from here.
        bot.setGravity(false);
        bot.setAllowFlight(true);
        bot.setFlying(true);
        bot.teleport(randomSpot(zone));
    }

    /** Somewhere inside the zone's own bounds, at the height its teleport stands at - the teleport itself sits on the zone's edge. */
    private static Location randomSpot(ZoneDefinition zone) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        var region = zone.region();
        double x = random.nextDouble(region.minX() + 2.0, Math.max(region.minX() + 2.5, region.maxX() - 1.0));
        double z = random.nextDouble(region.minZ() + 2.0, Math.max(region.minZ() + 2.5, region.maxZ() - 1.0));
        return new Location(region.world(), x, zone.teleport().getY(), z, random.nextFloat() * 360f, 0f);
    }

    private void remove(UUID id) {
        NmsBridge.Handle handle = bots.remove(id);
        zoneOfBot.remove(id);
        if (handle == null) {
            return;
        }
        try {
            nms.leave(handle);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not remove " + handle.player().getName(), e);
        }
        PacketEvents.getAPI().getProtocolManager().removeUser(handle.channel());
        handle.channel().close();
        SyntheticPlayers.remove(id);
    }

    private void tick() {
        tickCount++;
        for (int i = 0; i < SPAWNS_PER_TICK && !spawnQueue.isEmpty(); i++) {
            spawnQueue.removeFirst().run();
        }
        if (bots.isEmpty()) {
            return;
        }
        boolean wanderTick = tickCount % WANDER_INTERVAL_TICKS == 0;
        boolean tapTick = tickCount % TAP_INTERVAL_TICKS == 0;
        int index = 0;
        for (Map.Entry<UUID, NmsBridge.Handle> entry : List.copyOf(bots.entrySet())) {
            NmsBridge.Handle handle = entry.getValue();
            Player bot = handle.player();
            if (tickPlayers) {
                try {
                    nms.tick(handle);
                } catch (Exception e) {
                    tickPlayers = false;
                    plugin.getLogger().log(Level.WARNING, "Ticking bots failed - they stay put from here on", e);
                }
            }
            // Fixed shares of the bots, so the same ones keep wandering/tapping.
            double slice = (index++ % 100) / 100.0;
            if (wanderTick && slice < wanderShare) {
                ZoneDefinition zone = zones.getZones().get(zoneOfBot.get(entry.getKey()));
                if (zone != null) {
                    bot.teleport(randomSpot(zone));
                }
            }
            // Everyone looks around: a small turn every half second, the
            // way a player's camera is never quite still.
            if (tickCount % LOOK_INTERVAL_TICKS == index % LOOK_INTERVAL_TICKS) {
                Location at = bot.getLocation();
                bot.setRotation(at.getYaw() + ThreadLocalRandom.current().nextFloat(-25f, 25f), at.getPitch());
            }
            if (tapTick && slice >= 1.0 - tapShare) {
                OreCube target = zones.getCubeService().currentTarget(bot);
                if (target != null) {
                    zones.getTapService().tap(bot, target);
                }
            }
        }
    }

    private void sampleTraffic() {
        long[] now = {TrafficSink.PLUGIN_PACKETS.sum(), TrafficSink.PLUGIN_BYTES.sum(), TrafficSink.VANILLA_PACKETS.sum()};
        for (int i = 0; i < 3; i++) {
            trafficWindow[trafficSlot][i] = now[i] - lastTraffic[i];
            lastTraffic[i] = now[i];
        }
        trafficSlot = (trafficSlot + 1) % trafficWindow.length;
    }

    /** Per second over the last minute: plugin packets, plugin bytes, server packets - all to bots. */
    double[] trafficPerSecond() {
        double[] totals = new double[3];
        for (long[] second : trafficWindow) {
            for (int i = 0; i < 3; i++) {
                totals[i] += second[i];
            }
        }
        for (int i = 0; i < 3; i++) {
            totals[i] /= trafficWindow.length;
        }
        return totals;
    }

    Map<String, Integer> botsByZone() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UUID id : bots.keySet()) {
            counts.merge(zoneOfBot.getOrDefault(id, "?"), 1, Integer::sum);
        }
        return counts;
    }

    /** One line a script can grep: everything a run needs recording. */
    String reportLine() {
        ServerHealth health = ServerHealth.read();
        double[] traffic = trafficPerSecond();
        int count = Math.max(1, bots.size());
        StringBuilder top = new StringBuilder();
        int shown = 0;
        for (PerfTracker.SectionStats section : PerfTracker.sections()) {
            if (shown++ >= 6) {
                break;
            }
            top.append(String.format(Locale.ROOT, " %s=%.2f", section.system(), section.msPerTick()));
        }
        top.append(" | pkt/s");
        shown = 0;
        for (PerfTracker.PacketStats packets : PerfTracker.packetsBySystem()) {
            if (shown++ >= 6) {
                break;
            }
            top.append(String.format(Locale.ROOT, " %s=%.0f", packets.type(), packets.perSecond()));
        }
        return String.format(Locale.ROOT,
                "LOADTEST bots=%d online=%d tps=%.2f mspt=%.2f p95=%.2f max=%.2f heap=%dMB tracked=%.2fms"
                        + " pluginPkt/s=%.0f pluginKB/s=%.1f perBotKB/s=%.2f vanillaPkt/s=%.0f dbQueue=%d |%s",
                bots.size(), health.online(), health.tps1m(), health.msptAvg(), health.msptP95(), health.msptMax(),
                health.heapUsedMb(), health.trackedMsPerTick(),
                traffic[0], traffic[1] / 1024.0, traffic[1] / 1024.0 / count, traffic[2], health.dbQueued(), top);
    }

    /** Deletes every document any bot ever wrote to the shared player collection. */
    void cleanupData(Runnable done) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= MAX_BOTS; i++) {
            ids.add(idFor(i));
        }
        var database = JavaPlugin.getPlugin(YieldCore.class).getDatabaseManager();
        database.supplyAsync(() -> database.getCollection("playerData")
                        .deleteMany(com.mongodb.client.model.Filters.in("_id", ids)).getDeletedCount())
                .thenAccept(deleted -> Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().info("Deleted " + deleted + " bot document(s).");
                    done.run();
                }));
    }
}
