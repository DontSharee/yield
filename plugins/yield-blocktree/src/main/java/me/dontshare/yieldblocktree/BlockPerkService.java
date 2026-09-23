package me.dontshare.yieldblocktree;

import me.dontshare.yieldblocktree.data.BlockPerk;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeEffect;
import me.dontshare.yieldblocktree.data.BlockTreeEffectType;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.shard.ShardType;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.cube.OreCube;
import me.dontshare.yieldzones.data.CubeTier;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where every {@link BlockPerk} actually happens. Each perk is either a
 * provider plugged into a system that already asks "how much?" (damage,
 * coins, crit, spawn odds, slots...) or a reaction to a kill or a tap.
 * <p>
 * Everything is asked through {@link BlockTreeService#perkValue}, which is
 * cached, so a player without a perk costs one map lookup per question.
 */
public final class BlockPerkService implements Listener {

    private static final String KEY = "blocktree-perks";

    /** Stonewall: how long "standing still" has to last. */
    private static final long STILL_MILLIS = 3_000L;
    /** Resonance: the window distinct blocks are counted over, and the most it counts. */
    private static final long RESONANCE_WINDOW_MILLIS = 60_000L;
    private static final int RESONANCE_MAX_TYPES = 5;
    /** Soul Link: kills per +1% damage. */
    private static final long SOUL_LINK_KILLS_PER_PERCENT = 1_000L;
    private static final long HIGH_TIDE_MILLIS = 60_000L;
    private static final long WARLORD_MILLIS = 5 * 60_000L;
    private static final long TRICK_MILLIS = 10_000L;
    private static final double TRICK_DAMAGE = 0.5;
    private static final double TREAT_TIMES = 5.0;
    private static final long WISP_MILLIS = 30_000L;
    private static final double WISP_COINS = 1.0;
    private static final double BEACON_RADIUS = 16.0;
    private static final double MIDAS_TIMES = 10.0;
    private static final double MOTHERLODE_TIMES = 5.0;
    private static final double STALACTITE_TIMES = 10.0;
    private static final double WILDFIRE_HP_SHARE = 0.25;
    private static final double AVALANCHE_HP_SHARE = 0.5;

    private final JavaPlugin plugin;
    private final BlockTreeService service;
    private final BlockTreeFeedback feedback;
    private final YieldPacks packs;
    private final YieldZones zones;
    private final java.util.function.Supplier<Map<Material, BlockTreeDefinition>> content;

    private final Map<UUID, Long> lastMovedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Hit>> recentBreaks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> killCounter = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTideAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> warlordUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> trickUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> wispUntil = new ConcurrentHashMap<>();

    private record Hit(Material material, long at) {
    }

    public BlockPerkService(JavaPlugin plugin, BlockTreeService service, BlockTreeFeedback feedback, YieldPacks packs,
                            YieldZones zones, java.util.function.Supplier<Map<Material, BlockTreeDefinition>> content) {
        this.plugin = plugin;
        this.service = service;
        this.feedback = feedback;
        this.packs = packs;
        this.zones = zones;
        this.content = content;
    }

    // ---- wiring ----

    public void register() {
        packs.registerDamageMultiplierProvider(KEY, profile -> 1.0 + damageBonus(profile));
        packs.registerCoinMultiplierProvider(KEY, profile -> 1.0 + coinBonus(profile));
        packs.registerAutoSwitchSpeedMultiplierProvider(KEY, profile -> 1.0 + perk(profile, BlockPerk.SLIPSTREAM));
        packs.getEnchantService().registerBonusSlotProvider(KEY,
                profile -> (int) Math.round(perk(profile, BlockPerk.ENCHANTERS_GIFT)));
        packs.getEquipmentService().registerBonusEquipSlotsProvider(KEY,
                profile -> (int) Math.round(perk(profile, BlockPerk.EXTRA_HAND)));
        packs.getEnchantMarketService().registerPriceMultiplierProvider(KEY,
                player -> Math.max(0.0, 1.0 - service.perkValue(player.getUniqueId(), BlockPerk.HAGGLER)));

        var cubes = zones.getCubeService();
        cubes.registerExtraCubeCapProvider(KEY, profile -> (int) Math.round(perk(profile, BlockPerk.QUARRY)));
        cubes.registerCubeBonusChanceBoostProvider(KEY, profile -> perk(profile, BlockPerk.WEEPING_FORTUNE));
        cubes.registerSpawnWeightMultiplierProvider(KEY, this::spawnWeight);
        cubes.registerRespawnDelayMultiplierProvider(KEY,
                profile -> Math.max(0.05, 1.0 - perk(profile, BlockPerk.SECOND_WIND)));
        zones.getPetCombatController().registerCritChanceProvider(KEY, profile -> perk(profile, BlockPerk.TEMPERED_EDGE));

        var taps = zones.getTapService();
        taps.registerTapMultiplierProvider(KEY, (player, profile) -> 1.0 + perk(profile, BlockPerk.FROSTBITE));
        taps.registerTapListener(KEY, this::onTap);
    }

    public void unregister() {
        packs.unregisterDamageMultiplierProvider(KEY);
        packs.unregisterCoinMultiplierProvider(KEY);
        packs.unregisterAutoSwitchSpeedMultiplierProvider(KEY);
        packs.getEnchantService().unregisterBonusSlotProvider(KEY);
        packs.getEquipmentService().unregisterBonusEquipSlotsProvider(KEY);
        packs.getEnchantMarketService().unregisterPriceMultiplierProvider(KEY);
        var cubes = zones.getCubeService();
        cubes.unregisterExtraCubeCapProvider(KEY);
        cubes.unregisterCubeBonusChanceBoostProvider(KEY);
        cubes.unregisterSpawnWeightMultiplierProvider(KEY);
        cubes.unregisterRespawnDelayMultiplierProvider(KEY);
        zones.getPetCombatController().unregisterCritChanceProvider(KEY);
        zones.getTapService().unregisterTapMultiplierProvider(KEY);
        zones.getTapService().unregisterTapListener(KEY);
    }

    private double perk(PackPlayerProfile profile, BlockPerk perk) {
        return profile == null ? 0.0 : service.perkValue(profile.getPlayerId(), perk);
    }

    private static boolean active(Map<UUID, Long> until, UUID id) {
        Long end = until.get(id);
        return end != null && end > System.currentTimeMillis();
    }

    // ---- providers ----

    private double damageBonus(PackPlayerProfile profile) {
        UUID id = profile.getPlayerId();
        double bonus = 0.0;

        double stonewall = service.perkValue(id, BlockPerk.STONEWALL);
        if (stonewall > 0) {
            Long moved = lastMovedAt.get(id);
            if (moved == null || System.currentTimeMillis() - moved >= STILL_MILLIS) {
                bonus += stonewall;
            }
        }
        double resonance = service.perkValue(id, BlockPerk.RESONANCE);
        if (resonance > 0) {
            bonus += resonance * Math.min(RESONANCE_MAX_TYPES, distinctRecentBlocks(id));
        }
        double roots = service.perkValue(id, BlockPerk.DEEP_ROOTS);
        if (roots > 0) {
            bonus += roots * profile.getUnlockedZoneIds().size();
        }
        double soul = service.perkValue(id, BlockPerk.SOUL_LINK);
        if (soul > 0) {
            bonus += Math.min(soul, 0.01 * (profile.getLifetimeCubeKills() / SOUL_LINK_KILLS_PER_PERCENT));
        }
        if (active(warlordUntil, id)) {
            bonus += service.perkValue(id, BlockPerk.WARLORD);
        }
        if (active(trickUntil, id)) {
            bonus += TRICK_DAMAGE;
        }
        return bonus;
    }

    private double coinBonus(PackPlayerProfile profile) {
        UUID id = profile.getPlayerId();
        double bonus = beaconAura(id);
        if (active(wispUntil, id)) {
            bonus += WISP_COINS;
        }
        return bonus;
    }

    /** The strongest aura this player is standing in - their own, or any Beacon Aura holder within range. Auras don't stack. */
    private double beaconAura(UUID id) {
        double best = service.perkValue(id, BlockPerk.BEACON_AURA);
        Player self = Bukkit.getPlayer(id);
        if (self == null) {
            return best;
        }
        Location at = self.getLocation();
        double rangeSquared = BEACON_RADIUS * BEACON_RADIUS;
        for (Player other : self.getWorld().getPlayers()) {
            if (other == self) {
                continue;
            }
            double aura = service.perkValue(other.getUniqueId(), BlockPerk.BEACON_AURA);
            if (aura > best && other.getLocation().distanceSquared(at) <= rangeSquared) {
                best = aura;
            }
        }
        return best;
    }

    private Double spawnWeight(PackPlayerProfile profile, CubeTier tier) {
        if (tier.treasure()) {
            return 1.0 + perk(profile, BlockPerk.TREASURE_SENSE);
        }
        // Big safes and boss blocks are the giants that carry their own glow.
        if (tier.giant() && tier.glow() != null) {
            return 1.0 + perk(profile, BlockPerk.RELIC_HUNTER);
        }
        return 1.0;
    }

    private int distinctRecentBlocks(UUID id) {
        Deque<Hit> hits = recentBreaks.get(id);
        if (hits == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - RESONANCE_WINDOW_MILLIS;
        Set<Material> seen = new HashSet<>();
        synchronized (hits) {
            for (Hit hit : hits) {
                if (hit.at() >= cutoff) {
                    seen.add(hit.material());
                }
            }
        }
        return seen.size();
    }

    // ---- taps ----

    private void onTap(Player player, PackPlayerProfile profile, OreCube cube, double damage) {
        UUID id = player.getUniqueId();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        var cubes = zones.getCubeService();

        double stalactite = service.perkValue(id, BlockPerk.STALACTITE);
        if (stalactite > 0 && random.nextDouble() < stalactite) {
            cubes.queueDamage(player, cube, Math.round(damage * (STALACTITE_TIMES - 1)), null);
            player.playSound(player.getLocation(), Sound.BLOCK_POINTED_DRIPSTONE_LAND, 1f, 0.8f);
        }
        double echo = service.perkValue(id, BlockPerk.ECHO);
        if (echo > 0 && random.nextDouble() < echo) {
            for (OreCube other : cubes.liveCubes(player)) {
                if (other != cube) {
                    cubes.queueDamage(player, other, Math.round(damage), null);
                }
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.8f);
        }
    }

    // ---- kills ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(OreCubeKilledEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = packs.getPlayerStore().getCached(id);
        if (profile == null) {
            return;
        }
        CubeTier tier = event.getTier();
        long coins = event.getCoinsEarned();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        long extraCoins = 0;
        long extraDiamonds = 0;

        if (service.hasPerk(id, BlockPerk.RESONANCE)) {
            Deque<Hit> hits = recentBreaks.computeIfAbsent(id, k -> new ArrayDeque<>());
            synchronized (hits) {
                hits.addLast(new Hit(tier.material(), now));
                while (!hits.isEmpty() && hits.peekFirst().at() < now - RESONANCE_WINDOW_MILLIS) {
                    hits.pollFirst();
                }
            }
        }

        double midas = service.perkValue(id, BlockPerk.MIDAS_TOUCH);
        if (midas > 0 && random.nextDouble() < midas) {
            long bonus = Math.round(coins * (MIDAS_TIMES - 1));
            extraCoins += bonus;
            announce(player, BlockPerk.MIDAS_TOUCH, "<gold>+" + Formatting.format((double) bonus) + " coins</gold>");
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.4f);
        }
        double clone = service.perkValue(id, BlockPerk.SHADOW_CLONE);
        if (clone > 0 && random.nextDouble() < clone) {
            extraCoins += coins;
            extraDiamonds += event.getDiamondsEarned();
            player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.2f);
        }
        double molten = service.perkValue(id, BlockPerk.MOLTEN_CORE);
        if (molten > 0 && tier.giant()) {
            extraCoins += Math.round(coins * molten);
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_POP, 1f, 0.8f);
        }
        double radiance = service.perkValue(id, BlockPerk.RADIANCE);
        double bonusMultiplier = event.getBonusMultiplier();
        if (radiance > 0 && bonusMultiplier > 1.0) {
            // The share of the payout the golden/diamond bonus added, paid again.
            extraCoins += Math.round(coins * (bonusMultiplier - 1.0) / bonusMultiplier * radiance);
        }
        double tide = service.perkValue(id, BlockPerk.HIGH_TIDE);
        if (tide > 0) {
            Long last = lastTideAt.get(id);
            if (last == null || now - last >= HIGH_TIDE_MILLIS) {
                lastTideAt.put(id, now);
                long bonus = Math.round(coins * Math.max(0.0, tide - 1.0));
                extraCoins += bonus;
                player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7f, 1.2f);
            }
        }
        double trick = service.perkValue(id, BlockPerk.TRICK_OR_TREAT);
        if (trick > 0 && random.nextDouble() < trick) {
            if (random.nextInt(3) < 2) {
                long bonus = Math.round(coins * (TREAT_TIMES - 1));
                extraCoins += bonus;
                announce(player, BlockPerk.TRICK_OR_TREAT, "<gold>Treat! +" + Formatting.format((double) bonus) + " coins</gold>");
            } else {
                trickUntil.put(id, now + TRICK_MILLIS);
                announce(player, BlockPerk.TRICK_OR_TREAT, "<red>Trick! +50% damage for 10s</red>");
            }
            player.playSound(player.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.7f, 1.3f);
        }
        double wisp = service.perkValue(id, BlockPerk.WISP);
        if (wisp > 0 && random.nextDouble() < wisp) {
            wispUntil.put(id, now + WISP_MILLIS);
            announce(player, BlockPerk.WISP, "<gold>+100% coins for 30s</gold>");
            player.playSound(player.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1f);
            player.spawnParticle(Particle.SOUL_FIRE_FLAME, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
        }
        double motherlode = service.perkValue(id, BlockPerk.MOTHERLODE);
        if (motherlode > 0 && event.getDiamondsEarned() > 0 && random.nextDouble() < motherlode) {
            extraDiamonds += Math.round(event.getDiamondsEarned() * (MOTHERLODE_TIMES - 1));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 1.6f);
        }

        pay(profile, extraCoins, extraDiamonds);

        double geode = service.perkValue(id, BlockPerk.GEODE);
        if (geode > 0 && random.nextDouble() < geode) {
            ShardType[] types = ShardType.values();
            feedback.giveShard(player, types[random.nextInt(types.length)], false);
        }
        double archaeologist = service.perkValue(id, BlockPerk.ARCHAEOLOGIST);
        if (archaeologist > 0) {
            packs.getEnchantService().tryDropBook(player, archaeologist, packs.getLuckService().totalLuckMultiplier(profile));
        }
        double safecracker = service.perkValue(id, BlockPerk.SAFECRACKER);
        if (safecracker > 0 && tier.material() == sourceOf(BlockPerk.SAFECRACKER)) {
            packs.getEnchantService().tryDropBook(player, safecracker, packs.getLuckService().totalLuckMultiplier(profile), "rare");
        }
        double scholar = service.perkValue(id, BlockPerk.SCHOLAR);
        if (scholar > 0 && !profile.getEquippedPetIds().isEmpty()) {
            packs.getPetLevelingService().grantKillXp(player, new LinkedHashSet<>(profile.getEquippedPetIds()),
                    Math.max(1L, Math.round(tier.xpValue() * scholar)));
        }
        double stash = service.perkValue(id, BlockPerk.STASH);
        if (stash > 0 && random.nextDouble() < stash) {
            stashEgg(player);
        }
        if (service.hasPerk(id, BlockPerk.WARLORD) && tier.material() == sourceOf(BlockPerk.WARLORD)) {
            warlordUntil.put(id, now + WARLORD_MILLIS);
            player.showTitle(Title.title(Text.parse(BlockPerk.WARLORD.title()),
                    Text.parse("<red>+" + Formatting.format(service.perkValue(id, BlockPerk.WARLORD) * 100) + "% damage for 5 minutes</red>"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(400))));
            player.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.9f);
        }

        // Area damage lands next tick: this event fires from inside a
        // damage flush, and queuing and flushing again from in here would
        // re-enter it.
        double avalanche = service.perkValue(id, BlockPerk.AVALANCHE);
        if (avalanche >= 1) {
            long count = killCounter.merge(id, 1L, Long::sum);
            if (count % Math.round(avalanche) == 0) {
                Bukkit.getScheduler().runTask(plugin, () -> avalanche(player));
            }
        }
        double wildfire = service.perkValue(id, BlockPerk.WILDFIRE);
        if (wildfire > 0 && random.nextDouble() < wildfire) {
            Bukkit.getScheduler().runTask(plugin, () -> wildfire(player));
        }
    }

    private void pay(PackPlayerProfile profile, long coins, long diamonds) {
        if (coins <= 0 && diamonds <= 0) {
            return;
        }
        if (coins > 0) {
            BigInteger amount = BigInteger.valueOf(coins);
            profile.setCoins(profile.getCoins().add(amount));
            profile.setLifetimeCoinsEarned(profile.getLifetimeCoinsEarned().add(amount));
        }
        if (diamonds > 0) {
            profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(diamonds)));
        }
        packs.getPlayerStore().save(profile.getPlayerId());
    }

    private void avalanche(Player player) {
        if (!player.isOnline()) {
            return;
        }
        var cubes = zones.getCubeService();
        List<OreCube> live = cubes.liveCubes(player);
        if (live.isEmpty()) {
            return;
        }
        for (OreCube cube : live) {
            cubes.queueDamage(player, cube, Math.max(1L, Math.round(cube.tier().maxHp() * AVALANCHE_HP_SHARE)), null);
        }
        cubes.flushDamage(player);
        announce(player, BlockPerk.AVALANCHE, "<white>Your cubes took a beating.</white>");
        player.playSound(player.getLocation(), Sound.BLOCK_SNOW_BREAK, 1f, 0.5f);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.6f);
    }

    private void wildfire(Player player) {
        if (!player.isOnline()) {
            return;
        }
        var cubes = zones.getCubeService();
        Location at = player.getLocation();
        OreCube nearest = null;
        double best = Double.MAX_VALUE;
        for (OreCube cube : cubes.liveCubes(player)) {
            if (!cube.location().getWorld().equals(at.getWorld())) {
                continue;
            }
            double distance = cube.location().distanceSquared(at);
            if (distance < best) {
                best = distance;
                nearest = cube;
            }
        }
        if (nearest == null) {
            return;
        }
        cubes.queueDamage(player, nearest, Math.max(1L, Math.round(nearest.tier().maxHp() * WILDFIRE_HP_SHARE)), null);
        cubes.flushDamage(player);
        player.spawnParticle(Particle.FLAME, nearest.center(), 25, 0.3, 0.3, 0.3, 0.03);
        player.playSound(nearest.location(), Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.1f);
    }

    /** One of the current zone's own eggs - the one its treasure chest hands out. */
    private void stashEgg(Player player) {
        ZoneDefinition zone = zones.getCubeService().currentZoneOf(player);
        if (zone == null) {
            return;
        }
        for (CubeTier tier : zone.cubeTiers()) {
            if (tier.treasure() && tier.rewardPackId() != null) {
                packs.getPackOpenService().grantHatch(player, tier.rewardPackId(), 1);
                announce(player, BlockPerk.STASH, "<white>+1 free egg</white>");
                player.playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1.2f);
                return;
            }
        }
    }

    /** The block whose tree grants {@code perk} - where a "when you kill this block" perk looks for its trigger. */
    private Material sourceOf(BlockPerk perk) {
        for (BlockTreeDefinition def : content.get().values()) {
            for (var tier : def.tiers()) {
                for (BlockTreeEffect effect : tier.effects()) {
                    if (effect.type() == BlockTreeEffectType.PERK && perk.name().equals(effect.data())) {
                        return def.material();
                    }
                }
            }
        }
        return null;
    }

    private void announce(Player player, BlockPerk perk, String detail) {
        Component line = Text.parse(perk.title() + " <dark_gray>»</dark_gray> <detail>", Placeholder.parsed("detail", detail));
        player.sendMessage(line);
    }

    // ---- bookkeeping ----

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            lastMovedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastMovedAt.remove(id);
        recentBreaks.remove(id);
        killCounter.remove(id);
        lastTideAt.remove(id);
        warlordUntil.remove(id);
        trickUntil.remove(id);
        wispUntil.remove(id);
        service.invalidatePerks(id);
    }
}
