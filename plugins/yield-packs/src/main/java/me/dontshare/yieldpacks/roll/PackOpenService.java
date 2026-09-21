package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.enchant.EnchantService;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.mastery.MasteryService;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
public final class PackOpenService {

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
    private final EnchantService enchantService;
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
                            EnchantService enchantService, MasteryService masteryService) {
        this.plugin = plugin;
        this.content = content;
        this.store = store;
        this.rollService = rollService;
        this.revealService = revealService;
        this.enchantService = enchantService;
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

    public void unregisterHatchSiteProvider(String key) {
        hatchSiteProviders.remove(key);
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
        long intervalTicks = Math.max(1L, content.get().shop().openCooldownMillis() / 50L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::autoHatchTick, intervalTicks, intervalTicks);
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

        PackRollService.PurchaseResult result = rollService.hatch(player, packId, tier, true);
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
        // Both fire once PER EGG, exactly as that many separate hatches
        // would have, so book drops and Mastery XP stay identical however
        // the eggs were batched.
        for (int i = 0; i < result.rolls().size(); i++) {
            enchantService.maybeDropBook(player, result.luckMultiplier());
            masteryService.grantXp(player, MasteryType.PACKS, 1);
        }

        PackDefinition pack = content.get().packs().getOrThrow(packId);
        // hatch() already advanced rollCount once per egg - the pity bar
        // shown during the reveal must reflect where the count stood BEFORE
        // this batch.
        long rollCountBefore = profile.getRollCount() - result.rolls().size();
        if (profile.isRollAnimationEnabled()) {
            revealService.playHatch(player, pack, result.rolls(), result.luckMultiplier(), rollCountBefore);
        } else if (result.rolls().size() == 1) {
            // Animations off still means the action bar cycles the real
            // odds and the pity bar - never silence.
            revealService.playCompactReel(player, pack, result.rolls().get(0).item(),
                    result.luckMultiplier(), rollCountBefore);
        }
        sendSummary(player, packId, result);
        Bukkit.getPluginManager().callEvent(new PackOpenedEvent(player, packId, result.rolls()));
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
    private void autoHatchTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PackPlayerProfile profile = store.getCached(player.getUniqueId());
            if (profile == null || !profile.isAutoOpenEnabled()) {
                continue;
            }
            String packId = hatchSiteFor(player);
            if (packId == null) {
                continue;
            }
            // Exactly the rung they picked - see PackPlayerProfile#getAutoHatchAmount
            // for why this never quietly settles for fewer.
            int amount = Math.min(profile.getAutoHatchAmount(), maxTierFor(player));
            if (rollService.affordableHatches(player, packId, amount) < amount) {
                continue;
            }
            tryHatch(player, packId, amount);
        }
    }
}
