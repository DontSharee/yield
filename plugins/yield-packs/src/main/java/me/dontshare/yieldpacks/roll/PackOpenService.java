package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.mastery.MasteryService;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single entry point for hatching an egg. Everything goes through
 * {@link #tryHatch} - smacking a station, a tier button in the hatch menu,
 * and the auto-hatch loop alike - so the cooldown, the reveal and the
 * roll-animation toggle apply identically however the hatch was asked for.
 * <p>
 * The cooldown is charged ONCE PER ACTION rather than once per egg: a 24x
 * hatch is one action. That matters more than it used to. Eggs are paid for
 * at the moment they hatch now, so with a one-egg-per-action cooldown the
 * whole game would be rate-limited to one pet per second no matter how rich
 * a player got - and a zone's eggs are deliberately priced at about 1/150th
 * of a minute's income in that zone, so a settled player can afford roughly
 * two and a half per second. Bulk tiers are what keep the loop bound by
 * income (which scales with the player) instead of by the clock (which does
 * not); that is why everything up to {@link #UNGATED_TIER_CAP} is free and
 * only the top 24x rung sits behind {@link #MULTI_OPEN_PERMISSION}.
 */
public final class PackOpenService implements Listener {

    /** The permission the 24x rung needs - granted by the {@code multi_open_pass} gamepass (see yield-achievements' store.yml), same "bare permission node, no ownership registry" shape as {@code yieldpacks.autofuse}/{@code yieldpacks.automode}. */
    public static final String MULTI_OPEN_PERMISSION = "yieldpacks.multiopen";

    /** The rungs the hatch menu offers, in order. Everything up to {@link #UNGATED_TIER_CAP} is free to everyone. */
    public static final int[] HATCH_TIERS = {1, 5, 10, PackRollService.MULTI_OPEN_CAP};

    /**
     * The largest hatch a player without the gamepass may perform in one
     * action.
     * <p>
     * Raised from 5 once hatching became something you do standing still.
     * A zone's eggs cost about 1/150th of a minute's income there, so a
     * minute of mining funds ~150 hatches, and one action per second of
     * cooldown turns that into time spent AT the egg rather than earning:
     * 50% of the mining time at 5x, 25% at 10x, 10% at the gamepass's 24x.
     * At a cap of 5 the pass was worth roughly a quarter of a player's
     * progression rate, which is a gate rather than a convenience. At 10 it
     * saves about a tenth - real, worth buying, and not the difference
     * between keeping up and not.
     */
    public static final int UNGATED_TIER_CAP = 10;

    private final JavaPlugin plugin;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final PackRollService rollService;
    private final PackRevealAnimationService revealService;
    private final MasteryService masteryService;
    private final Map<UUID, Long> lastHatchAtMillis = new ConcurrentHashMap<>();

    /**
     * Which egg, if any, each player is currently standing at - registered
     * by yield-packstations, which owns the physical stations and so is the
     * only thing that can answer it. Keyed and composable like every other
     * provider registry here, and a provider returning null simply means
     * "this player isn't at one of mine".
     * <p>
     * Inverted on purpose: yield-packstations depends on yield-packs, never
     * the other way round, so the auto-hatch loop asks rather than looks.
     */
    private final Map<String, Function<Player, String>> hatchSiteProviders = new ConcurrentHashMap<>();
    /** Where each provider's egg physically stands - see {@link #registerHatchSiteProvider(String, Function, Function)}. */
    private final Map<String, Function<Player, org.bukkit.Location>> hatchSiteLocators = new ConcurrentHashMap<>();
    /** The egg an auto-hatch session is tied to - see {@link #autoHatchTick}. */
    private record AutoSession(String packId, org.bukkit.Location at) {
    }
    private final Map<java.util.UUID, AutoSession> autoSessions = new ConcurrentHashMap<>();
    /** How far a player can walk from the egg they're auto-hatching before it switches off. */
    public static final double AUTO_HATCH_LEASH = 15.0;

    /**
     * Shortens the hatch cooldown for a player - each registered factor
     * (yield-skilltree's ROLL_SPEED_MULTIPLIER nodes, yield-achievements'
     * potions, ...) multiplies together, 1.0 being unmodified and higher
     * being faster. Keyed the same way as YieldPacks' own coin/damage/
     * attack-speed provider maps, so more than one plugin can contribute at
     * once instead of silently clobbering whichever registered last.
     */
    private final Map<String, Function<PackPlayerProfile, Double>> cooldownMultiplierProviders = new ConcurrentHashMap<>();

    public PackOpenService(JavaPlugin plugin, Supplier<PackContentLoader.ContentSnapshot> content,
                            PlayerDataStore<PackPlayerProfile> store, PackRollService rollService,
                            PackRevealAnimationService revealService,
                            MasteryService masteryService) {
        this.plugin = plugin;
        this.content = content;
        this.store = store;
        this.rollService = rollService;
        this.revealService = revealService;
        this.masteryService = masteryService;
    }

    public void registerCooldownMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        cooldownMultiplierProviders.put(key, provider);
    }

    public void unregisterCooldownMultiplierProvider(String key) {
        cooldownMultiplierProviders.remove(key);
    }

    /** See {@link #hatchSiteProviders}. */
    public void registerHatchSiteProvider(String key, Function<Player, String> provider) {
        hatchSiteProviders.put(key, provider);
    }

    /** Same as {@link #registerHatchSiteProvider(String, Function)}, plus where the egg it names stands - what the auto-hatch leash measures from. */
    public void registerHatchSiteProvider(String key, Function<Player, String> provider, Function<Player, org.bukkit.Location> locator) {
        hatchSiteProviders.put(key, provider);
        hatchSiteLocators.put(key, locator);
    }

    public void unregisterHatchSiteProvider(String key) {
        hatchSiteProviders.remove(key);
        hatchSiteLocators.remove(key);
    }

    /** Where the egg the player is standing at stands, or null. */
    private org.bukkit.Location hatchSiteLocation(Player player) {
        for (Map.Entry<String, Function<Player, String>> entry : hatchSiteProviders.entrySet()) {
            if (entry.getValue().apply(player) != null) {
                Function<Player, org.bukkit.Location> locator = hatchSiteLocators.get(entry.getKey());
                return locator != null ? locator.apply(player) : null;
            }
        }
        return null;
    }

    /** The egg the player is standing at right now, or null if they aren't at one. */
    public String hatchSiteFor(Player player) {
        for (Function<Player, String> provider : hatchSiteProviders.values()) {
            String packId = provider.apply(player);
            if (packId != null) {
                return packId;
            }
        }
        return null;
    }

    private double cooldownMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : cooldownMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** The largest rung this player may hatch in one action. */
    public int maxTierFor(Player player) {
        return player.hasPermission(MULTI_OPEN_PERMISSION) ? PackRollService.MULTI_OPEN_CAP : UNGATED_TIER_CAP;
    }

    /** Runs the auto-hatch loop at roughly the cooldown's own cadence. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long intervalTicks = Math.max(1L, content.get().shop().openCooldownMillis() / 50L);
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("hatch.auto", this::autoHatchTick), intervalTicks, intervalTicks);
    }

    /**
     * Hatches {@code count} of {@code packId} for {@code player}, charging
     * for them, and shows the result. Silently rejects (no message) if
     * called too soon after the last hatch - the station communicates its
     * own readiness visually, and a hold-to-smack player would otherwise be
     * told off several times a second.
     * <p>
     * A hatch still playing out is NOT a reason to refuse: the new one
     * simply takes the screen from it (see {@code
     * PackRevealAnimationService#playHatch}). The cooldown is the only
     * limit, which is the one a player can shorten and the one the balance
     * assumes.
     */
    public PackRollService.PurchaseResult tryHatch(Player player, String packId, int count) {
        return tryHatch(player, packId, count, true);
    }

    /**
     * Whether a hatch asked for right now would actually happen - i.e. the
     * cooldown has elapsed.
     * <p>
     * For a caller that takes payment in its own currency (a seasonal
     * event's Candy, say): it has to know the hatch will go through BEFORE
     * charging, because the cooldown refuses silently and often, and
     * charging for a hatch that then does not occur is the one failure mode
     * a currency must never have.
     */
    public boolean readyToHatch(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        long cooldownMillis = Math.round(content.get().shop().openCooldownMillis() / cooldownMultiplier(profile));
        return System.currentTimeMillis() - lastHatchAtMillis.getOrDefault(player.getUniqueId(), 0L) >= cooldownMillis
                && !revealService.isRevealPending(player.getUniqueId());
    }

    /** @param charge false when the caller has already taken payment in a currency this service knows nothing about - see {@code PackStationService.AlternateCharge}. */
    public PackRollService.PurchaseResult tryHatch(Player player, String packId, int count, boolean charge) {
        int tier = Math.min(count, maxTierFor(player));
        if (tier < count) {
            return PackRollService.PurchaseResult.failure("You need the Multi-Hatch gamepass to hatch "
                    + count + " at once.");
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        long cooldownMillis = Math.round(content.get().shop().openCooldownMillis() / cooldownMultiplier(profile));
        long now = System.currentTimeMillis();
        if (now - lastHatchAtMillis.getOrDefault(player.getUniqueId(), 0L) < cooldownMillis) {
            return PackRollService.PurchaseResult.failure(null);
        }
        // The last batch hasn't been shown yet - see
        // PackRevealAnimationService#isRevealPending. Silent, like the
        // cooldown: the station just takes the hatch a moment later.
        if (revealService.isRevealPending(player.getUniqueId())) {
            return PackRollService.PurchaseResult.failure(null);
        }

        PackRollService.PurchaseResult result = rollService.hatch(player, packId, tier, charge);
        if (!result.success()) {
            player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            return result;
        }
        lastHatchAtMillis.put(player.getUniqueId(), now);
        finish(player, profile, packId, result);
        return result;
    }

    /**
     * Hatches eggs nobody paid coins for - a treasure chest's burst (see
     * yield-zones' OreCubeService), or an admin grant. No cooldown and no
     * gamepass gate, because neither is what earned it, but the same reveal
     * and the same events: a pet from a chest is a pet.
     */
    public PackRollService.PurchaseResult grantHatch(Player player, String packId, int count) {
        PackRollService.PurchaseResult result = rollService.hatch(player, packId, count, false);
        if (!result.success()) {
            return result;
        }
        finish(player, store.getOrCreate(player.getUniqueId()), packId, result);
        return result;
    }

    /** The half every hatch shares once the pets are actually rolled and paid for. */
    private void finish(Player player, PackPlayerProfile profile, String packId,
                         PackRollService.PurchaseResult result) {
        // Once PER EGG, exactly as that many separate hatches would have,
        // so Mastery XP stays identical however the eggs were batched.
        // Enchant Books no longer drop here - they come from breaking cubes
        // and the Enchant Market (see EnchantService#tryDropBook).
        masteryService.grantXp(player, MasteryType.PACKS, result.rolls().size());

        PackDefinition pack = content.get().packs().getOrThrow(packId);
        // hatch() already advanced rollCount once per egg - the pity bar
        // shown during the reveal must reflect where the count stood BEFORE
        // this batch.
        long rollCountBefore = profile.getRollCount() - result.rolls().size();
        Runnable reveal = pendingReveal(player, () -> {
            sendSummary(player, packId, result);
            Bukkit.getPluginManager().callEvent(new PackOpenedEvent(player, packId, result.rolls()));
        });
        if (profile.isRollAnimationEnabled()) {
            revealService.playHatch(player, pack, result.rolls(), result.luckMultiplier(), rollCountBefore,
                    profile.isRarePetAnimationEnabled(), reveal);
        } else if (result.rolls().size() == 1) {
            // Animations off still means the action bar cycles the real
            // odds and the pity bar - never silence.
            revealService.playCompactReel(player, pack, result.rolls().get(0).item(),
                    result.luckMultiplier(), rollCountBefore, reveal);
        } else {
            reveal.run();
        }
    }

    /**
     * Hatches whose reveal hasn't happened yet, per player - see
     * {@link #pendingReveal}.
     */
    private final Map<UUID, List<Runnable>> pendingReveals = new ConcurrentHashMap<>();

    /**
     * Wraps what a hatch announces - the chat summary and the
     * {@link PackOpenedEvent} the rare-pull broadcast, quests, the tutorial
     * and the event plugin all hang off - so it happens once, at the
     * reveal, not the instant the eggs were paid for. Sent immediately, it
     * told the player (and, through the broadcast, the whole server) what
     * was in the eggs while they were still shaking.
     * <p>
     * If the player logs off before their eggs crack, the reveal is flushed
     * on the way out (see {@link #onQuit}) rather than later: after a quit
     * the player's data is saved and unloaded, and a listener calling
     * getOrCreate then would cache a blank profile the autosave could
     * write over the real one. Flushing first keeps the progress without
     * that risk.
     */
    private Runnable pendingReveal(Player player, Runnable announce) {
        UUID id = player.getUniqueId();
        AtomicBoolean done = new AtomicBoolean();
        Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            List<Runnable> pending = pendingReveals.get(id);
            if (pending != null) {
                pending.remove(self[0]);
            }
            announce.run();
        };
        pendingReveals.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(self[0]);
        return self[0];
    }

    /** LOWEST, so it runs before any plugin's store saves and unloads this player - see {@link #pendingReveal}. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        List<Runnable> pending = pendingReveals.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            for (Runnable reveal : pending) {
                reveal.run();
            }
        }
    }

    private void sendSummary(Player player, String packId, PackRollService.PurchaseResult result) {
        if (result.rolls().size() == 1) {
            player.sendMessage(Text.parse(
                    "<#4BD9FF><bold>Eggs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Hatched <pack>: <result></gray>",
                    Placeholder.unparsed("pack", packName(packId)),
                    Placeholder.unparsed("result", Formatting.stripLeadingColorCodes(result.rolls().get(0).item().displayName()))));
            return;
        }
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Eggs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Hatched <count>x <pack>!</gray>",
                Placeholder.unparsed("count", String.valueOf(result.rolls().size())),
                Placeholder.unparsed("pack", packName(packId))));
    }

    private String packName(String packId) {
        String raw = content.get().packs().find(packId).map(PackDefinition::displayName).orElse(packId);
        return Formatting.stripLeadingColorCodes(raw);
    }

    /**
     * Auto-hatch only runs while a player is actually standing at an egg,
     * and hatches the rung they picked in the hatch menu. That is the whole
     * shape of the idle loop now: turn it on, pick an amount, stand at an
     * egg - rather than the old "toggle it on anywhere and drain a
     * stockpile".
     */
    /**
     * Auto-hatch is tied to the egg it started at: it keeps hatching that
     * egg while the player stays within {@link #AUTO_HATCH_LEASH} blocks of
     * it (so stepping back from the crowd around a station doesn't pause
     * it), and switches itself OFF once they walk further - rather than
     * quietly resuming at whatever egg they happen to pass next.
     */
    private void autoHatchTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            java.util.UUID id = player.getUniqueId();
            PackPlayerProfile profile = store.getCached(id);
            if (profile == null || !profile.isAutoOpenEnabled()) {
                autoSessions.remove(id);
                continue;
            }
            AutoSession session = autoSessions.get(id);
            if (session == null) {
                String packId = hatchSiteFor(player);
                if (packId == null) {
                    continue;
                }
                org.bukkit.Location at = hatchSiteLocation(player);
                session = new AutoSession(packId, at != null ? at : player.getLocation());
                autoSessions.put(id, session);
            } else if (!player.getWorld().equals(session.at().getWorld())
                    || player.getLocation().distanceSquared(session.at()) > AUTO_HATCH_LEASH * AUTO_HATCH_LEASH) {
                autoSessions.remove(id);
                profile.setAutoOpenEnabled(false);
                store.save(id);
                player.sendMessage(me.dontshare.yieldcore.text.Text.parse(
                        "<gray>Auto Hatch <red>off</red> - you walked away from the egg.</gray>"));
                continue;
            }
            // Exactly the rung they picked - see PackPlayerProfile#getAutoHatchAmount
            // for why this never quietly settles for fewer.
            int amount = Math.min(profile.getAutoHatchAmount(), maxTierFor(player));
            var storage = rollService.storage();
            if (storage != null && !storage.hasRoom(player, profile, amount)) {
                autoSessions.remove(id);
                profile.setAutoOpenEnabled(false);
                store.save(id);
                player.sendMessage(me.dontshare.yieldcore.text.Text.parse("<gray>Auto Hatch <red>off</red> - <msg></gray>",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("msg", storage.fullMessage(player, profile))));
                continue;
            }
            if (rollService.affordableHatches(player, session.packId(), amount) < amount) {
                continue;
            }
            tryHatch(player, session.packId(), amount);
        }
    }

    /** The station this player is auto-hatching at right now, or null - what an egg's "disable auto hatch" bar keys off. */
    public org.bukkit.Location autoHatchSite(Player player) {
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null || !profile.isAutoOpenEnabled()) {
            return null;
        }
        AutoSession session = autoSessions.get(player.getUniqueId());
        return session != null ? session.at() : null;
    }

    /** Switches auto hatch off for this player, as if they had toggled it in the hatch menu. */
    public void stopAutoHatch(Player player) {
        java.util.UUID id = player.getUniqueId();
        autoSessions.remove(id);
        PackPlayerProfile profile = store.getCached(id);
        if (profile != null && profile.isAutoOpenEnabled()) {
            profile.setAutoOpenEnabled(false);
            store.save(id);
        }
    }

    @org.bukkit.event.EventHandler
    public void onQuitClearAutoSession(org.bukkit.event.player.PlayerQuitEvent event) {
        autoSessions.remove(event.getPlayer().getUniqueId());
    }
}
