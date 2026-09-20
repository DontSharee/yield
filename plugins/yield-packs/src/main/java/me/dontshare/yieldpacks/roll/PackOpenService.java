package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.enchant.EnchantService;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.gui.PackMultiOpenResultGui;
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
 * The single entry point for opening a pack - the compass, the storage
 * dialog's "Open 1" and auto-open all funnel through {@link #tryOpen}, so
 * the cooldown, the reveal and the roll-animation toggle apply identically
 * however the open was asked for. {@link #tryOpenMany}
 * is the one bulk exception - gated behind the {@code yieldpacks.multiopen}
 * gamepass permission, it reuses the same suppression/cooldown guards but
 * rolls up to {@code PackRollService#MULTI_OPEN_CAP} packs in one go and
 * reveals the whole haul at once - as an in-world grid floating in front
 * of the player (same packet-display machinery as the single-open reel,
 * minus the spin: the packs have already been opened, so what's left to
 * show is the haul, not suspense), or as a chest-grid summary for players
 * who have turned roll animations off.
 */
public final class PackOpenService {

    /** The permission a player needs for {@link #tryOpenMany} - granted by the {@code multi_open_pass} gamepass (see yield-achievements' store.yml), same "bare permission node, no ownership registry" shape as {@code yieldpacks.autofuse}/{@code yieldpacks.automode}. */
    public static final String MULTI_OPEN_PERMISSION = "yieldpacks.multiopen";

    /** Where a call to {@link #tryOpen} came from - decides which reveal (if any) plays. See {@code PackRevealAnimationService}/{@code RollAnimationService}'s own class docs for why this matters. */
    public enum OpenTrigger { MANUAL_COMPASS, DIALOG, AUTO_OPEN }

    private final JavaPlugin plugin;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final PackRollService rollService;
    private final PackRevealAnimationService reelAnimationService;
    private final EnchantService enchantService;
    private final MasteryService masteryService;
    private final Map<UUID, Long> lastOpenAtMillis = new ConcurrentHashMap<>();
    // Only ever used as the no-animation fallback for tryOpenMany - set
    // after construction because the GUI is built later in YieldPacks'
    // startup (same setter-injection idiom as OpenPackDialog's own
    // PackStorageGui). Nullable, and treated as such: a missing GUI
    // degrades to the chat summary every bulk open already sends, never
    // an NPE mid-open.
    private PackMultiOpenResultGui multiOpenResultGui;

    /**
     * Shortens the open cooldown for a player - each registered factor
     * (yield-skilltree's ROLL_SPEED_MULTIPLIER nodes, yield-achievements'
     * potions, ...) multiplies together, 1.0 being unmodified and higher
     * being faster. Keyed the same way as YieldPacks' own coin/damage/
     * attack-speed provider maps, so more than one plugin can contribute at
     * once instead of silently clobbering whichever registered last - this
     * used to be a single mutable slot until potions needed to also
     * contribute here alongside the skill tree.
     */
    private final Map<String, Function<PackPlayerProfile, Double>> cooldownMultiplierProviders = new ConcurrentHashMap<>();

    public PackOpenService(JavaPlugin plugin, Supplier<PackContentLoader.ContentSnapshot> content,
                            PlayerDataStore<PackPlayerProfile> store, PackRollService rollService,
                            PackRevealAnimationService reelAnimationService,
                            EnchantService enchantService, MasteryService masteryService) {
        this.plugin = plugin;
        this.content = content;
        this.store = store;
        this.rollService = rollService;
        this.reelAnimationService = reelAnimationService;
        this.enchantService = enchantService;
        this.masteryService = masteryService;
    }

    /** See {@link #multiOpenResultGui}. Keeps the "which reveal plays" decision in this one class, alongside tryOpen's reel/compact-reel split, rather than duplicating the roll-animation check in every caller. */
    public void setMultiOpenResultGui(PackMultiOpenResultGui multiOpenResultGui) {
        this.multiOpenResultGui = multiOpenResultGui;
    }

    public void registerCooldownMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        cooldownMultiplierProviders.put(key, provider);
    }

    public void unregisterCooldownMultiplierProvider(String key) {
        cooldownMultiplierProviders.remove(key);
    }

    private double cooldownMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : cooldownMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Runs the auto-open loop at roughly the cooldown's own cadence. */
    public void start() {
        long intervalTicks = Math.max(1L, content.get().shop().openCooldownMillis() / 50L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::autoOpenTick, intervalTicks, intervalTicks);
    }

    /**
     * Attempts to open one {@code packId} for {@code player} - silently
     * rejects (no message; the compass communicates its own cooldown via
     * Minecraft's built-in item-cooldown overlay, see PackSelectorService)
     * if called too soon after the last open. Used for both a manual "Open
     * 1" click and each tick of the auto-open loop.
     */
    public boolean tryOpen(Player player, String packId, OpenTrigger trigger) {
        if (RevealSuppressionRegistry.isActive(player.getUniqueId())) {
            // A reel (see PackRevealAnimationService) is still mid-flight for
            // this player - a rare result's reel can run well past the
            // open cooldown (JACKPOT ~6-7s vs. a 1s default cooldown), and
            // now that AUTO_OPEN plays the same reel as a manual open, the
            // auto-open tick would otherwise stack a second full reel on
            // top of the first every ~1s while nothing but common items
            // are involved... this guard makes that impossible regardless
            // of which trigger is calling in.
            return false;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        long cooldownMillis = Math.round(content.get().shop().openCooldownMillis() / cooldownMultiplier(profile));
        long now = System.currentTimeMillis();
        long last = lastOpenAtMillis.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMillis) {
            return false;
        }

        PackRollService.PurchaseResult result = rollService.openOneFromStorage(player, packId);
        if (!result.success()) {
            player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            return false;
        }
        lastOpenAtMillis.put(player.getUniqueId(), now);
        enchantService.maybeDropBook(player, result.luckMultiplier());
        masteryService.grantXp(player, MasteryType.PACKS, 1);

        PackRollService.RollResult roll = result.rolls().get(0);
        // Every trigger gets the same reveal. Auto-open is just a manual
        // open the player didn't have to click for, and a "Open 1" from the
        // storage dialog is the same single pack as a compass press - the
        // dialog used to answer with a Title instead, which meant the one
        // screen a player picks their quantity on was also the one that
        // never showed them the reel. The roll-animation toggle only
        // decides whether the reveal is the full in-world reel or just the
        // action-bar-only cycling (still real odds, still the pity bar),
        // never silence.
        if (trigger == OpenTrigger.DIALOG) {
            // The reel plays in the world - the dialog has to be out of the
            // way to see it. (Paper closes a dialog on a button click by
            // default; this covers the rest.)
            player.closeDialog();
            player.closeInventory();
        }
        PackDefinition pack = content.get().packs().getOrThrow(packId);
        // openOneFromStorage already incremented rollCount for this roll -
        // the pity bar shown throughout the reel must reflect the count as
        // it stood BEFORE this roll.
        long rollCountBefore = profile.getRollCount() - 1;
        if (profile.isRollAnimationEnabled()) {
            reelAnimationService.playReel(player, pack, roll.item(), result.luckMultiplier(), rollCountBefore);
        } else {
            reelAnimationService.playCompactReel(player, pack, roll.item(), result.luckMultiplier(), rollCountBefore);
        }
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Packs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Opened <pack>: <result></gray>",
                Placeholder.unparsed("pack", packName(packId)),
                Placeholder.unparsed("result", Formatting.stripLeadingColorCodes(roll.item().displayName()))));
        Bukkit.getPluginManager().callEvent(new PackOpenedEvent(player, packId, result.rolls()));
        return true;
    }

    /**
     * The bulk counterpart to {@link #tryOpen} - opens up to {@code
     * min(count, PackRollService#MULTI_OPEN_CAP, stored)} packs in one
     * action for the full-screen results grid (see {@code
     * PackMultiOpenResultGui}), instead of the usual single-item reveal.
     * Requires {@link #MULTI_OPEN_PERMISSION}. The suppression guard and
     * cooldown are checked/applied exactly ONCE for the whole batch, not
     * once per pack - the batch IS the convenience a player is paying for.
     * {@code enchantService}/{@code masteryService} still fire once PER
     * roll, same as {@code count} sequential single opens would produce, so
     * drop odds and Mastery XP stay identical either way. Returns the raw
     * {@link PackRollService.PurchaseResult} (not a boolean, unlike {@link
     * #tryOpen}) so the caller can render every roll.
     */
    public PackRollService.PurchaseResult tryOpenMany(Player player, String packId, int count) {
        if (!player.hasPermission(MULTI_OPEN_PERMISSION)) {
            return PackRollService.PurchaseResult.failure("You need the Multi-Open gamepass for this.");
        }
        if (RevealSuppressionRegistry.isActive(player.getUniqueId())) {
            return PackRollService.PurchaseResult.failure("Still finishing your last reveal.");
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        long cooldownMillis = Math.round(content.get().shop().openCooldownMillis() / cooldownMultiplier(profile));
        long now = System.currentTimeMillis();
        long last = lastOpenAtMillis.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMillis) {
            return PackRollService.PurchaseResult.failure("You're opening too fast - wait a moment.");
        }

        PackRollService.PurchaseResult result = rollService.openManyFromStorage(player, packId, count);
        if (!result.success()) {
            player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            return result;
        }
        lastOpenAtMillis.put(player.getUniqueId(), now);
        for (int i = 0; i < result.rolls().size(); i++) {
            enchantService.maybeDropBook(player, result.luckMultiplier());
            masteryService.grantXp(player, MasteryType.PACKS, 1);
        }
        if (profile.isRollAnimationEnabled()) {
            // The grid is drawn in the world, in front of the player, so
            // every screen has to be out of the way first - the dialog the
            // click came from, and whatever GUI was behind it.
            player.closeDialog();
            player.closeInventory();
            PackDefinition pack = content.get().packs().getOrThrow(packId);
            reelAnimationService.playMultiReveal(player, pack, result.rolls(), result.luckMultiplier());
        } else if (multiOpenResultGui != null) {
            multiOpenResultGui.open(player, result.rolls());
        }
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Packs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Opened <count>x <pack>!</gray>",
                Placeholder.unparsed("count", String.valueOf(result.rolls().size())),
                Placeholder.unparsed("pack", packName(packId))));
        Bukkit.getPluginManager().callEvent(new PackOpenedEvent(player, packId, result.rolls()));
        return result;
    }

    private String packName(String packId) {
        String raw = content.get().packs().find(packId).map(PackDefinition::displayName).orElse(packId);
        return Formatting.stripLeadingColorCodes(raw);
    }

    private void autoOpenTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PackPlayerProfile profile = store.getCached(player.getUniqueId());
            if (profile == null || !profile.isAutoOpenEnabled()) {
                continue;
            }
            String packId = profile.getActivePackId();
            int stored = packId != null ? profile.getStoredPacks().getOrDefault(packId, 0) : 0;
            if (stored <= 0) {
                // Nothing to open on the currently active pack (or none
                // selected, or storage is simply empty) - auto-open stays
                // ON regardless and just idles: re-derive whichever pack is
                // worth the most right now, so the moment this player
                // acquires ANY pack (bought, gifted, whatever) the very
                // next tick picks it up automatically with no need to
                // re-toggle. Only a genuinely empty storage skips this tick.
                String best = rollService.bestStoredPackId(profile);
                if (best == null) {
                    continue;
                }
                profile.setActivePackId(best);
                packId = best;
            }
            tryOpen(player, packId, OpenTrigger.AUTO_OPEN);
        }
    }
}
