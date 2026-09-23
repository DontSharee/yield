package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.pity.PityService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The "cool CS:GO-style" pack-opening reveal - a small fixed strip of
 * item-display + text-display slots that follows directly in front of the
 * player for the reveal's whole duration, whose CONTENT (not position) is
 * swapped every step to simulate a scrolling reel, decelerating as it
 * approaches an already-known landing result. The roll itself already
 * happened instantly server-side (see
 * {@code PackRollService#openOneFromStorage}) - this is purely a delayed,
 * cosmetic reveal of that result, same idea the old title-based
 * {@link RollAnimationService} already used, just far more elaborate.
 * <p>
 * Only ever triggered for the manual compass open (see
 * {@code PackOpenService.OpenTrigger#MANUAL_COMPASS}) - never for auto-open
 * (which must stay instant) or the storage-GUI dialog (which keeps the
 * older, compact {@link RollAnimationService} reveal).
 */
public final class PackRevealAnimationService {

    private static final int SLOT_COUNT = 7;
    private static final int CENTER_INDEX = 3;
    // Widened from the original 0.85 - the 3-line rarity/name/odds labels
    // (see #slotLabel) are noticeably wider than the old single-line name,
    // and at 0.85 adjacent slots' text visibly crowded/overlapped from the
    // camera's angle even though each slot IS a genuinely separate entity.
    private static final float SLOT_SPACING = 1.15f;
    private static final double FORWARD_DISTANCE = 2.6;
    private static final int PRE_HOLD_TICKS = 6;
    private static final float ITEM_SCALE = 0.55f;
    private static final float ITEM_PULSE_SCALE = 0.50f;
    /** The winning slot's own scale once the reel lands - noticeably bigger than every mid-cycle slot, on top of every other slot being removed outright, so which pet was actually won is unambiguous. */
    private static final float WIN_SCALE = 0.9f;
    /** How often the whole strip re-anchors to the player's current position/facing - see #track. */
    private static final int TRACK_INTERVAL_TICKS = 1;

    private enum Tier { STANDARD, BIG, JACKPOT }

    private record TierConfig(int steps, int holdTicks, double baseDelayTicks, double rampTicks,
                               Sound sound, float pitch, Particle particle, int particleCount) {
    }

    /**
     * Everything one in-flight reel needs, threaded through the recursive
     * step-by-step task chain and the separate per-tick tracking task.
     * {@code trackingTaskHolder} is a one-element mutable box (a record
     * field can't itself be reassigned) so #despawn can cancel the tracking
     * task started just after this context is built. {@code spawnEntities}
     * is false for a "compact" reel (roll animation disabled in settings) -
     * the action-bar cycling, sounds, and pity bar always play regardless;
     * only the in-world item/text strip in front of the player is gated by
     * this flag, per the user's own distinction between the two.
     */
    private record ReelContext(Player player, UUID playerId, int[] itemIds, int[] textIds,
                                List<ItemDefinition> sequence, TierConfig tierConfig, Map<String, Double> oddsByItemId,
                                long rollCountBeforeThisRoll, double speedMultiplier, boolean spawnEntities,
                                BukkitTask[] trackingTaskHolder, Runnable onRevealed) {
    }

    private final JavaPlugin plugin;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PackRollService rollService;
    private final PityService pityService;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final ItemIconFactory iconFactory = new ItemIconFactory();

    public PackRevealAnimationService(JavaPlugin plugin, Supplier<PackContentLoader.ContentSnapshot> content,
                                       PackRollService rollService, PityService pityService,
                                       Supplier<RarityRegistry> rarityRegistry) {
        this.plugin = plugin;
        this.content = content;
        this.rollService = rollService;
        this.pityService = pityService;
        this.rarityRegistry = rarityRegistry;
    }

    /**
     * The action-bar-only reveal - cycling odds, pity bar and sounds, with
     * no in-world entities at all. For a player who has turned the roll
     * animation off but should still never be told nothing happened.
     * <p>
     * All that survives of the old spinning reel, which the hatch replaced:
     * the reel existed to manufacture suspense around a single unknown, and
     * an egg that shakes before it cracks does that better and means it.
     */
    /** {@code onRevealed} runs once, the moment the reel lands on the pet - see PackOpenService#finish. */
    public void playCompactReel(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                                 long rollCountBeforeThisRoll, Runnable onRevealed) {
        start(player, pack, finalItem, luckMultiplier, rollCountBeforeThisRoll, 1.0, false, onRevealed);
    }

    private void start(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                        long rollCountBeforeThisRoll, double speedMultiplier, boolean spawnEntities,
                        Runnable onRevealed) {
        Rarity finalRarity = rarityRegistry.get().find(finalItem.rarityId()).orElse(null);
        TierConfig tierConfig = configFor(tierFor(finalRarity));

        List<PackRollService.WeightedOdds> odds = rollService.oddsFor(pack, luckMultiplier);
        Map<String, Double> oddsByItemId = new HashMap<>();
        for (PackRollService.WeightedOdds w : odds) {
            oddsByItemId.put(w.item().id(), w.probability());
        }
        List<ItemDefinition> sequence = buildSequence(odds, finalItem, tierConfig.steps());

        int[] itemIds = new int[SLOT_COUNT];
        int[] textIds = new int[SLOT_COUNT];
        if (spawnEntities) {
            for (int j = 0; j < SLOT_COUNT; j++) {
                itemIds[j] = PacketEntityManager.nextEntityId();
                textIds[j] = PacketEntityManager.nextEntityId();
            }
        }

        UUID playerId = player.getUniqueId();
        RevealSuppressionRegistry.begin(playerId);

        BukkitTask[] trackingTaskHolder = new BukkitTask[1];
        if (spawnEntities) {
            Location[] slots = slotLocations(player);
            PacketEntityManager.beginBundle(player);
            for (int j = 0; j < SLOT_COUNT; j++) {
                ItemDisplayManager.spawn(player, itemIds[j], slots[j]);
                ItemDisplayManager.setScale(player, itemIds[j], ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
                ItemDisplayManager.setRotation(player, itemIds[j], 0f, facingYawTowardPlayer(slots[j], player));
                ItemDisplayManager.setInterpolation(player, itemIds[j], 0, TRACK_INTERVAL_TICKS, TRACK_INTERVAL_TICKS);

                Location textPos = slots[j].clone().add(0, 0.4, 0);
                TextDisplayManager.spawn(player, textIds[j], textPos);
                TextDisplayManager.setBillboard(player, textIds[j], TextDisplayManager.Billboard.VERTICAL);
                TextDisplayManager.setBackgroundColor(player, textIds[j], 0x00000000);
                TextDisplayManager.setStyle(player, textIds[j], true, false, false, TextDisplayManager.Alignment.CENTER);
                TextDisplayManager.setInterpolation(player, textIds[j], 0, TRACK_INTERVAL_TICKS, TRACK_INTERVAL_TICKS);
            }
            PacketEntityManager.endBundle(player);
        }

        if (spawnEntities) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        }
        // Same layout PackActionBarService's own normal display uses -
        // "Selected: X | Pity: bar (n/max, next xM)" - only the leftmost
        // segment changes while a reel plays (here: "Rolling.."), everything
        // else (the separator, the word Pity, the bar, the progress suffix)
        // stays exactly where the player already expects to find it.
        player.sendActionBar(Text.parse(
                "<#4BD9FF>Rolling..</#4BD9FF> <gray>|</gray> <#4BD9FF>Pity: <bar> <gray><suffix></gray>",
                Placeholder.parsed("bar", pityService.renderBar(rollCountBeforeThisRoll)),
                Placeholder.unparsed("suffix", pityService.renderProgressSuffix(rollCountBeforeThisRoll))));

        ReelContext ctx = new ReelContext(player, playerId, itemIds, textIds, sequence, tierConfig, oddsByItemId,
                rollCountBeforeThisRoll, Math.max(0.05, speedMultiplier), spawnEntities, trackingTaskHolder, onRevealed);
        if (spawnEntities) {
            trackingTaskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> track(ctx), 0L, TRACK_INTERVAL_TICKS);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> step(ctx, 0), scaled(ctx, PRE_HOLD_TICKS));
    }

    /** Re-anchors the whole strip to the player's CURRENT eye position/facing every tick, and keeps every slot's item facing back at the player - runs independently of the slower content-cycling step chain below. */
    private void track(ReelContext ctx) {
        Player player = ctx.player();
        if (!player.isOnline()) {
            ctx.trackingTaskHolder()[0].cancel();
            return;
        }
        Location[] slots = slotLocations(player);
        for (int j = 0; j < SLOT_COUNT; j++) {
            PacketEntityManager.teleportEntity(player, ctx.itemIds()[j], slots[j]);
            ItemDisplayManager.setRotation(player, ctx.itemIds()[j], 0f, facingYawTowardPlayer(slots[j], player));
            PacketEntityManager.teleportEntity(player, ctx.textIds()[j], slots[j].clone().add(0, 0.4, 0));
        }
    }

    private Location[] slotLocations(Player player) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().setY(0).normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        Location anchor = eye.clone().add(forward.multiply(FORWARD_DISTANCE));

        Location[] slots = new Location[SLOT_COUNT];
        for (int j = 0; j < SLOT_COUNT; j++) {
            slots[j] = anchor.clone().add(right.clone().multiply((j - CENTER_INDEX) * SLOT_SPACING));
        }
        return slots;
    }

    /**
     * The yaw slot {@code itemPos} needs to visually face {@code player} -
     * computed per-slot via real vector math (each slot sits at a slightly
     * different position, so each needs a slightly different angle), the
     * exact same {@code atan2(-dx, dz)} formula {@code PetDisplayService
     * #yawTowards} already uses (proven correct there for pets facing an
     * attack target) plus the same fixed +180 model-orientation correction
     * {@code PetDisplayService#computeYaws} always applies regardless of
     * whether a pet is matching the owner's facing or facing a target - a
     * player-head item-display's own "front" is offset 180 from the raw
     * entity-yaw convention no matter which way you're trying to point it.
     * <p>
     * A previous version of this method reused the player's own raw yaw as
     * a shortcut (skipping the vector math since the strip sits directly
     * ahead of the player) - that shortcut turned out to only coincide with
     * the correct answer near yaw 0 and diverge at other angles, since
     * "the player's own facing direction" and "the direction from a given
     * slot back to the player" are only the same line when a slot sits
     * exactly on the player's forward axis, which side slots never do.
     */
    private float facingYawTowardPlayer(Location itemPos, Player player) {
        Location eye = player.getEyeLocation();
        double dx = eye.getX() - itemPos.getX();
        double dz = eye.getZ() - itemPos.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz)) + 180f;
    }

    private void step(ReelContext ctx, int i) {
        Player player = ctx.player();
        if (!player.isOnline()) {
            despawn(ctx);
            return;
        }
        if (ctx.spawnEntities()) {
            for (int j = 0; j < SLOT_COUNT; j++) {
                int idx = i - (CENTER_INDEX - j);
                ItemDefinition shown = (idx >= 0 && idx < ctx.sequence().size()) ? ctx.sequence().get(idx) : null;
                if (shown != null) {
                    ItemDisplayManager.setItem(player, ctx.itemIds()[j], iconFactory.baseIcon(shown).build());
                    TextDisplayManager.setText(player, ctx.textIds()[j], slotLabel(ctx, shown));
                } else {
                    ItemDisplayManager.setItem(player, ctx.itemIds()[j], new ItemStack(Material.AIR));
                    TextDisplayManager.setText(player, ctx.textIds()[j], Component.empty());
                }
                ItemDisplayManager.setScale(player, ctx.itemIds()[j], ITEM_PULSE_SCALE, ITEM_PULSE_SCALE, ITEM_PULSE_SCALE);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                for (int j = 0; j < SLOT_COUNT; j++) {
                    ItemDisplayManager.setScale(player, ctx.itemIds()[j], ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
                }
            }, 2L);
        }

        // A rising-pitch "tick" every step, same idea as a real slot
        // machine's reel clicks speeding up then slowing as it settles -
        // pitch climbs from 0.7 to 1.3 across the whole sequence. Silent
        // for a compact (spawnEntities=false) reel - roll animation off
        // means no sounds either, not just no in-world entities.
        if (ctx.spawnEntities()) {
            double progress = ctx.sequence().size() <= 1 ? 1.0 : (double) i / (ctx.sequence().size() - 1);
            float tickPitch = (float) (0.7 + 0.6 * progress);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, tickPitch);
        }

        ItemDefinition centered = ctx.sequence().get(i);
        Rarity centeredRarity = rarityRegistry.get().find(centered.rarityId()).orElse(null);
        double probability = ctx.oddsByItemId().getOrDefault(centered.id(), 1.0);
        long oneInN = probability > 0 ? Math.round(1.0 / probability) : 0;
        String colorHex = centeredRarity != null ? centeredRarity.colorHex() : "#FFFFFF";
        player.sendActionBar(Text.parse(
                "<" + colorHex + "><bold><name></bold> <gray>(1 in <n>)</gray></" + colorHex + "> <gray>|</gray> <#4BD9FF>Pity: <bar> <gray><suffix></gray>",
                Placeholder.parsed("bar", pityService.renderBar(ctx.rollCountBeforeThisRoll())),
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(centered.displayName())),
                Placeholder.unparsed("n", Formatting.format((double) oneInN)),
                Placeholder.unparsed("suffix", pityService.renderProgressSuffix(ctx.rollCountBeforeThisRoll()))));

        if (i >= ctx.sequence().size() - 1) {
            land(ctx);
            return;
        }
        long delay = stepDelayTicks(ctx, i);
        Bukkit.getScheduler().runTaskLater(plugin, () -> step(ctx, i + 1), delay);
    }

    /**
     * Removes every slot EXCEPT the winning center one outright (rather than
     * leaving all 7 on screen with no distinction, which reads as "you won
     * 7") and scales the winner up as the highlight.
     */
    private void land(ReelContext ctx) {
        Player player = ctx.player();
        TierConfig cfg = ctx.tierConfig();
        ctx.onRevealed().run();

        if (ctx.spawnEntities()) {
            PacketEntityManager.beginBundle(player);
            for (int j = 0; j < SLOT_COUNT; j++) {
                if (j == CENTER_INDEX) {
                    continue;
                }
                PacketEntityManager.destroyEntity(player, ctx.itemIds()[j]);
                PacketEntityManager.destroyEntity(player, ctx.textIds()[j]);
            }
            ItemDisplayManager.setInterpolation(player, ctx.itemIds()[CENTER_INDEX], 0, 4, 4);
            ItemDisplayManager.setScale(player, ctx.itemIds()[CENTER_INDEX], WIN_SCALE, WIN_SCALE, WIN_SCALE);
            PacketEntityManager.endBundle(player);
            player.spawnParticle(cfg.particle(), slotLocations(player)[CENTER_INDEX], cfg.particleCount(), 0.3, 0.3, 0.3, 0.02);
            player.playSound(player.getLocation(), cfg.sound(), 1f, cfg.pitch());
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> despawnWinner(ctx), scaled(ctx, cfg.holdTicks()));
    }

    /** Final cleanup once the hold period ends - only the winning slot is still alive at this point (see #land). */
    private void despawnWinner(ReelContext ctx) {
        BukkitTask trackingTask = ctx.trackingTaskHolder()[0];
        if (trackingTask != null) {
            trackingTask.cancel();
        }
        Player player = ctx.player();
        if (ctx.spawnEntities() && player.isOnline()) {
            PacketEntityManager.beginBundle(player);
            PacketEntityManager.destroyEntity(player, ctx.itemIds()[CENTER_INDEX]);
            PacketEntityManager.destroyEntity(player, ctx.textIds()[CENTER_INDEX]);
            PacketEntityManager.endBundle(player);
        }
        RevealSuppressionRegistry.end(ctx.playerId());
    }

    /** Full teardown of every still-alive slot - used only when a reel is aborted BEFORE it lands (the player went offline mid-cycle), since #land itself already reduces to just the winner for the normal path. */
    private void despawn(ReelContext ctx) {
        BukkitTask trackingTask = ctx.trackingTaskHolder()[0];
        if (trackingTask != null) {
            trackingTask.cancel();
        }
        Player player = ctx.player();
        if (ctx.spawnEntities() && player.isOnline()) {
            PacketEntityManager.beginBundle(player);
            for (int j = 0; j < SLOT_COUNT; j++) {
                PacketEntityManager.destroyEntity(player, ctx.itemIds()[j]);
                PacketEntityManager.destroyEntity(player, ctx.textIds()[j]);
            }
            PacketEntityManager.endBundle(player);
        }
        RevealSuppressionRegistry.end(ctx.playerId());
    }

    /** Ease-out deceleration, scaled by {@link ReelContext#speedMultiplier}: quick at the start, slowing as it nears the landing. */
    private long stepDelayTicks(ReelContext ctx, int i) {
        int stepCount = ctx.sequence().size();
        double t = stepCount <= 1 ? 1.0 : (double) i / (stepCount - 1);
        double ticks = ctx.tierConfig().baseDelayTicks() + ctx.tierConfig().rampTicks() * t * t;
        return Math.max(1L, Math.round(ticks * ctx.speedMultiplier()));
    }

    private long scaled(ReelContext ctx, long baseTicks) {
        return Math.max(1L, Math.round(baseTicks * ctx.speedMultiplier()));
    }

    /**
     * A sequence of {@code stepCount} items ending EXACTLY on
     * {@code finalItem} - every earlier entry is sampled proportional to the
     * pack's real odds (common items dominate the scroll, same as they
     * would a real roll), so what scrolls past looks statistically
     * plausible rather than lying about what's actually likely.
     */
    private List<ItemDefinition> buildSequence(List<PackRollService.WeightedOdds> odds, ItemDefinition finalItem, int stepCount) {
        List<ItemDefinition> sequence = new ArrayList<>(stepCount);
        for (int i = 0; i < stepCount - 1; i++) {
            sequence.add(sampleWeighted(odds));
        }
        sequence.add(finalItem);
        return sequence;
    }

    private ItemDefinition sampleWeighted(List<PackRollService.WeightedOdds> odds) {
        return WeightedRandom.pick(odds, PackRollService.WeightedOdds::probability).item();
    }

    /** Three lines: rarity (its own gradient display name), the item's own colored name, and its real "(1 in N)" odds for this roll - directly on the in-world label, not just the action bar, so which pet is which is legible without staring at the action bar. */
    private Component slotLabel(ReelContext ctx, ItemDefinition item) {
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String colorHex = rarity != null ? rarity.colorHex() : "#FFFFFF";
        double probability = ctx.oddsByItemId().getOrDefault(item.id(), 1.0);
        long oneInN = probability > 0 ? Math.round(1.0 / probability) : 0;

        Component rarityLine = rarity != null ? Text.parse(rarity.displayName()) : Component.empty();
        Component nameLine = Text.parse("<" + colorHex + "><bold><name></bold>",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(item.displayName())));
        Component oddsLine = Text.parse("<gray>(1 in <n>)</gray>",
                Placeholder.unparsed("n", Formatting.format((double) oneInN)));

        return rarityLine.append(Component.newline()).append(nameLine).append(Component.newline()).append(oddsLine);
    }

    // ------------------------------------------------------------------
    // The hatch
    // ------------------------------------------------------------------

    /** Eggs per row before wrapping - 6 keeps a full 24x hatch to four readable rows rather than one wall. */
    private static final int HATCH_COLUMNS = 6;
    /**
     * How long the eggs wobble before they crack. Must stay under the hatch
     * cooldown (packs.yml open-cooldown-seconds, 20 ticks): at 26 it was
     * longer, so anyone hatching steadily or on auto started the next batch
     * before the last one cracked - and never saw a pet come out at all.
     */
    private static final int SHAKE_TICKS = 14;
    /** One wobble every this many ticks - each is interpolated across the gap, so the egg is always moving. */
    private static final int WOBBLE_INTERVAL = 4;
    private static final float WOBBLE_DEGREES = 14f;
    /** A pull at or past this "1 in N" gets the big treatment, whatever its rarity tier says. */
    private static final long SPECIAL_ONE_IN = 1000L;
    /** Rarity sortOrder at or past which a pull gets the big treatment regardless of odds. */
    private static final int SPECIAL_SORT_ORDER = 4;
    /** How much larger a special pet ends up than an ordinary one. */
    private static final float SPECIAL_PET_SCALE_MULTIPLIER = 1.9f;

    /**
     * One hatch in flight. {@code labelOffsets} is how far above each slot's
     * centre its name sits - per slot, and rewritten at the crack, because
     * an egg, a pet and an enlarged special pet are three different sizes
     * and a label pinned to the egg's size floats over the first and sinks
     * into the last. {@code pop} holds the rare pull currently doing its
     * pop-to-screen, if any (see {@link #advancePop}).
     */
    private record HatchContext(Player player, UUID playerId, int count, int[] itemIds, int[] textIds,
                                 List<PackRollService.RollResult> rolls, BukkitTask[] trackingTaskHolder,
                                 double[] labelOffsets, boolean rareAnimation, Runnable onRevealed,
                                 RarePop[] pop, boolean[] cracked) {
    }

    /**
     * Whether this player's hatch on screen still hasn't shown them what
     * they got - its eggs haven't cracked, or a rare pull is mid-pop. A
     * new hatch must wait for that rather than replace it: replacing before
     * the crack is how steady and auto hatching showed nothing but shaking
     * eggs, and replacing mid-pop cuts off the best moment in the game. It
     * never blocks longer than one shake, or one pop on a rare pull.
     */
    public boolean isRevealPending(UUID playerId) {
        HatchContext ctx = activeHatches.get(playerId);
        return ctx != null && ctx.player().isOnline() && (!ctx.cracked()[0] || ctx.pop()[0] != null);
    }

    /** The one special pull in a batch that gets the pop-to-screen moment, and how far through it is. */
    private static final class RarePop {
        private final int slot;
        private final PackRollService.RollResult roll;
        private final float restScale;
        private final float popScale;
        private final Component label;
        private int elapsed;

        private RarePop(int slot, PackRollService.RollResult roll, float restScale, float popScale, Component label) {
            this.slot = slot;
            this.roll = roll;
            this.restScale = restScale;
            this.popScale = popScale;
            this.label = label;
        }
    }

    /**
     * The hatch currently on screen for each player, so the next one can
     * replace it.
     * <p>
     * Hatching is a hold-down action: a player leans on the station and
     * expects eggs to keep popping. Refusing a hatch while the last one is
     * still fading would cap the whole game at one batch per animation -
     * slower than the cooldown that is supposed to be the limit - so a new
     * hatch clears the old one off the screen instead, as soon as the old
     * one has cracked (see {@link #isRevealPending}): replacing it any
     * earlier means the player never sees what they hatched. Every scheduled step
     * below re-checks that its own context is still the live one before it
     * touches a packet, which is what makes a replaced hatch stop dead
     * rather than despawning entities the new one is using.
     */
    private final Map<UUID, HatchContext> activeHatches = new ConcurrentHashMap<>();

    private boolean isLive(HatchContext ctx) {
        return ctx.player().isOnline() && activeHatches.get(ctx.playerId()) == ctx;
    }

    /**
     * The reveal: a clutch of eggs drops into the air in front of the
     * player, shakes, and cracks open together to leave the pets behind.
     * <p>
     * One animation for every hatch, 1x through 24x - a single egg is the
     * same ceremony as twenty-four, just narrower. That replaced a pair of
     * reveals (a spinning reel for one, a straight-to-prize grid for many)
     * and is both truer to what the player is doing and simpler: the egg IS
     * the suspense now, so nothing needs to spin to manufacture any.
     * <p>
     * Every egg cracks on the SAME tick. An earlier pass staggered them
     * rarest-last on the theory that ordering gives the eye somewhere to
     * land; in game it just read as lag. One clutch, one moment.
     * <p>
     * Sound is per HATCH, never per egg. Twenty-four eggs each playing
     * their own crack is not twenty-four times as exciting, it is noise -
     * so the batch plays one shell-crack and one fanfare, pitched to the
     * best thing in it.
     * <p>
     * The egg display and the pet display are the SAME packet entity -
     * hatching swaps the item on it rather than destroying one entity and
     * spawning another, which keeps a 24x hatch at 24 entities instead of
     * 48 and makes the swap land on exactly the tick the crack does.
     */
    /**
     * @param rareAnimation whether a special pull gets the pop-to-screen
     *                      moment - the player's /settings choice
     * @param onRevealed    runs exactly once, the tick the eggs crack - the
     *                      moment the player finds out what they got, so
     *                      the chat summary and the rare-pull broadcast wait
     *                      for it rather than giving the reveal away
     */
    public void playHatch(Player player, PackDefinition pack, List<PackRollService.RollResult> rolls,
                           double luckMultiplier, long rollCountBefore, boolean rareAnimation, Runnable onRevealed) {
        if (rolls.isEmpty()) {
            onRevealed.run();
            return;
        }
        int count = rolls.size();
        int[] itemIds = new int[count];
        int[] textIds = new int[count];
        for (int i = 0; i < count; i++) {
            itemIds[i] = PacketEntityManager.nextEntityId();
            textIds[i] = PacketEntityManager.nextEntityId();
        }

        UUID playerId = player.getUniqueId();
        // Whatever was still on screen goes now, entities and all.
        clearHatch(playerId);
        RevealSuppressionRegistry.begin(playerId);

        ItemStack egg = iconFactory.headOrFallback(pack.headDatabaseId(), pack.material());
        float eggScale = eggScaleFor(count);
        Location[] slots = hatchSlotLocations(player, count);
        double[] labelOffsets = new double[count];
        Arrays.fill(labelOffsets, labelOffsetFor(egg, eggScale));
        PacketEntityManager.beginBundle(player);
        for (int i = 0; i < count; i++) {
            ItemDisplayManager.spawn(player, itemIds[i], slots[i]);
            ItemDisplayManager.setItem(player, itemIds[i], egg);
            ItemDisplayManager.setScale(player, itemIds[i], eggScale, eggScale, eggScale);
            ItemDisplayManager.setRotation(player, itemIds[i], 0f, facingYawTowardPlayer(slots[i], player));
            ItemDisplayManager.setInterpolation(player, itemIds[i], 0, TRACK_INTERVAL_TICKS, TRACK_INTERVAL_TICKS);

            // Spawned empty and filled at the moment the eggs crack - the
            // name is the thing the player is waiting for, so showing it
            // over an unhatched egg would give the whole reveal away.
            TextDisplayManager.spawn(player, textIds[i], slots[i].clone().add(0, labelOffsets[i], 0));
            TextDisplayManager.setBillboard(player, textIds[i], TextDisplayManager.Billboard.VERTICAL);
            TextDisplayManager.setBackgroundColor(player, textIds[i], 0x00000000);
            TextDisplayManager.setStyle(player, textIds[i], true, false, false, TextDisplayManager.Alignment.CENTER);
            TextDisplayManager.setInterpolation(player, textIds[i], 0, TRACK_INTERVAL_TICKS, TRACK_INTERVAL_TICKS);
        }
        PacketEntityManager.endBundle(player);

        BukkitTask[] trackingTaskHolder = new BukkitTask[1];
        HatchContext ctx = new HatchContext(player, playerId, count, itemIds, textIds, rolls, trackingTaskHolder,
                labelOffsets, rareAnimation, onRevealed, new RarePop[1], new boolean[1]);
        activeHatches.put(playerId, ctx);
        trackingTaskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> trackHatch(ctx), 0L, TRACK_INTERVAL_TICKS);

        player.playSound(player.getLocation(), Sound.ENTITY_CHICKEN_EGG, 0.7f, 0.7f);
        scheduleShake(ctx, slots);
        Bukkit.getScheduler().runTaskLater(plugin, () -> crackAll(ctx), SHAKE_TICKS);

        // Long enough to read a full grid, and longer for a bigger one -
        // plus the whole pop-to-screen if a rare pull is going to do one.
        boolean popPlanned = rareAnimation && rolls.stream().anyMatch(roll ->
                isSpecial(roll, rarityRegistry.get().find(roll.item().rarityId()).orElse(null)));
        long holdTicks = SHAKE_TICKS + 35L + 8L * Math.min(6, count) + (popPlanned ? POP_TOTAL_TICKS : 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> despawnHatch(ctx), holdTicks);
        // Same layout PackActionBarService and the compact reel both use -
        // only the leftmost segment changes while a hatch plays, so the
        // pity bar stays exactly where the player expects to find it.
        player.sendActionBar(Text.parse(
                "<#4BD9FF>Hatching..</#4BD9FF> <gray>|</gray> <#4BD9FF>Pity: <bar> <gray><suffix></gray>",
                Placeholder.parsed("bar", pityService.renderBar(rollCountBefore)),
                Placeholder.unparsed("suffix", pityService.renderProgressSuffix(rollCountBefore))));
    }

    /** Vanilla draws a text display at 1/40 block per font pixel at scale 1 - the same size as a nametag. */
    private static final double TEXT_BLOCKS_PER_PIXEL = 1.0 / 40.0;
    private static final float LABEL_MAX_SCALE = 0.6f;
    private static final float LABEL_MIN_SCALE = 0.3f;
    /** Clear air between the top of what's in a slot and the bottom of its name. */
    private static final double LABEL_GAP = 0.06;

    /**
     * How tall this item's model is in an item display at scale 1: a head
     * is an 8-pixel cube, half a block; a flat or block item fills its
     * whole 16 pixels, a full block.
     */
    private static double modelHeight(ItemStack item) {
        Material type = item.getType();
        return type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD ? 0.5 : 1.0;
    }

    /**
     * How far above a slot's centre its name goes: just over the top of
     * whatever is actually displayed there. Item displays are centred on
     * their position, so that top is half the rendered height up.
     * <p>
     * This used to be {@code eggScale x 0.85 + 0.12} for every slot, always
     * - roughly half a block of empty air over a small egg, more over the
     * smaller pet that replaced it, and INSIDE a special pet enlarged past
     * the egg's size.
     */
    private static double labelOffsetFor(ItemStack item, float scale) {
        return modelHeight(item) * scale / 2.0 + LABEL_GAP;
    }

    /**
     * The biggest label scale (capped at {@link #LABEL_MAX_SCALE}) at which
     * this pull's name still fits inside its own slot.
     * <p>
     * The old label scaled only with the egg, around 0.8 - a bold
     * "Sovereign Stag" at that size is about two blocks wide over eggs 1.2
     * blocks apart, so neighbouring names ran into each other. Fitting each
     * label to the slot spacing from the name's own width keeps every
     * name inside its column at every batch size.
     */
    private static float labelScaleFor(String name, long oneIn, double spacing) {
        int namePx = name.length() * 7;
        int oddsPx = ("(1 in " + Formatting.format((double) oneIn) + ")").length() * 6;
        double widestBlocks = Math.max(1, Math.max(namePx, oddsPx)) * TEXT_BLOCKS_PER_PIXEL;
        double fit = spacing * 0.92 / widestBlocks;
        return (float) Math.max(LABEL_MIN_SCALE, Math.min(LABEL_MAX_SCALE, fit));
    }

    private double slotSpacing(int count) {
        return eggScaleFor(count) * 1.45;
    }

    /** Wobbles every egg back and forth until the cracks start - each step interpolates across the gap, so they are never still. */
    private void scheduleShake(HatchContext ctx, Location[] slots) {
        for (int tick = 0; tick < SHAKE_TICKS; tick += WOBBLE_INTERVAL) {
            boolean left = (tick / WOBBLE_INTERVAL) % 2 == 0;
            // One quiet rattle for the whole clutch, and only on every
            // other wobble - per-egg was a machine-gun at 24x.
            boolean rattle = (tick / WOBBLE_INTERVAL) % 2 == 0;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player player = ctx.player();
                if (!isLive(ctx)) {
                    return;
                }
                PacketEntityManager.beginBundle(player);
                for (int i = 0; i < ctx.count(); i++) {
                    float yaw = facingYawTowardPlayer(slots[i], player) + (left ? -WOBBLE_DEGREES : WOBBLE_DEGREES);
                    ItemDisplayManager.setInterpolation(player, ctx.itemIds()[i], 0, WOBBLE_INTERVAL, WOBBLE_INTERVAL);
                    ItemDisplayManager.setRotation(player, ctx.itemIds()[i], left ? -6f : 6f, yaw);
                }
                PacketEntityManager.endBundle(player);
                if (rattle) {
                    player.playSound(player.getLocation(), Sound.BLOCK_STONE_HIT, 0.35f, 1.3f);
                }
            }, tick);
        }
    }

    /**
     * The whole clutch breaks open at once: every display swaps from egg to
     * pet in one packet bundle, with exactly two sounds for the batch -
     * one shell-crack, and one fanfare pitched to the best pull in it.
     * <p>
     * A pet worth shouting about (see {@link #isSpecial}) comes out bigger
     * than the rest and wearing a glowing outline in its own rarity colour,
     * so a Legendary in a wall of twenty-four commons is the thing the eye
     * goes to without anyone having to read a single label.
     */
    private void crackAll(HatchContext ctx) {
        // Before the liveness check, on purpose: a hatch replaced by the
        // next one, or a player who walked off, still hatched those pets,
        // and whatever waits on the reveal (the chat summary, the broadcast,
        // quest progress) must still happen - exactly once.
        ctx.onRevealed().run();
        ctx.cracked()[0] = true;
        Player player = ctx.player();
        if (!isLive(ctx)) {
            return;
        }
        Location[] slots = hatchSlotLocations(player, ctx.count());
        float petScale = eggScaleFor(ctx.count()) * 0.75f;
        double spacing = slotSpacing(ctx.count());
        int popSlot = ctx.rareAnimation() ? rarePopSlot(ctx) : -1;
        RarePop pop = null;

        PackRollService.RollResult best = ctx.rolls().stream()
                .max(Comparator.comparingLong(PackRollService.RollResult::oneIn))
                .orElse(ctx.rolls().get(0));
        TierConfig bestCfg = configFor(tierFor(rarityRegistry.get().find(best.item().rarityId()).orElse(null)));

        PacketEntityManager.beginBundle(player);
        for (int slot = 0; slot < ctx.count(); slot++) {
            PackRollService.RollResult roll = ctx.rolls().get(slot);
            Rarity rarity = rarityRegistry.get().find(roll.item().rarityId()).orElse(null);
            boolean special = isSpecial(roll, rarity);
            float scale = special ? petScale * SPECIAL_PET_SCALE_MULTIPLIER : petScale;

            ItemStack icon = iconFactory.baseIcon(roll.item()).build();
            ItemDisplayManager.setInterpolation(player, ctx.itemIds()[slot], 0, 3, 3);
            ItemDisplayManager.setItem(player, ctx.itemIds()[slot], icon);
            ItemDisplayManager.setScale(player, ctx.itemIds()[slot], scale, scale, scale);
            ItemDisplayManager.setRotation(player, ctx.itemIds()[slot], 0f, facingYawTowardPlayer(slots[slot], player));
            if (special) {
                ItemDisplayManager.setGlowColor(player, ctx.itemIds()[slot], glowColorFor(rarity));
                ItemDisplayManager.setGlowing(player, ctx.itemIds()[slot], true);
            }
            ctx.labelOffsets()[slot] = labelOffsetFor(icon, scale);
            float labelScale = labelScaleFor(Formatting.stripLeadingColorCodes(roll.item().displayName()), roll.oneIn(), spacing);
            TextDisplayManager.setInterpolation(player, ctx.textIds()[slot], 0, 3, 3);
            TextDisplayManager.setScale(player, ctx.textIds()[slot], labelScale, labelScale, labelScale);
            PacketEntityManager.teleportEntity(player, ctx.textIds()[slot], slots[slot].clone().add(0, ctx.labelOffsets()[slot], 0));
            if (slot == popSlot) {
                // Its name is held back until it lands back in its slot -
                // while it's in the player's face the title card is the name.
                float popScale = (float) (POP_VISIBLE_BLOCKS / modelHeight(icon));
                pop = new RarePop(slot, roll, scale, popScale, hatchSlotLabel(roll));
            } else {
                TextDisplayManager.setText(player, ctx.textIds()[slot], hatchSlotLabel(roll));
            }
        }
        PacketEntityManager.endBundle(player);

        // Particles stay per-egg (they are silent, and a grid of them IS
        // the confetti), but they thin out as the clutch grows so a 24x
        // hatch doesn't turn the screen white.
        int perEgg = Math.max(3, 12 / Math.max(1, ctx.count() / 4));
        for (int slot = 0; slot < ctx.count(); slot++) {
            PackRollService.RollResult roll = ctx.rolls().get(slot);
            boolean special = isSpecial(roll, rarityRegistry.get().find(roll.item().rarityId()).orElse(null));
            player.spawnParticle(Particle.ITEM_SNOWBALL, slots[slot], perEgg, 0.2, 0.2, 0.2, 0.05);
            if (special) {
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, slots[slot], 25, 0.35, 0.35, 0.35, 0.08);
            }
        }

        player.playSound(player.getLocation(), Sound.ENTITY_TURTLE_EGG_BREAK, 0.8f, 1.1f);
        player.playSound(player.getLocation(), bestCfg.sound(), 0.9f, bestCfg.pitch());
        ctx.pop()[0] = pop;
    }

    /**
     * Whether this pull deserves the big treatment - either its rarity is
     * high enough on its own, or it beat odds long enough that its rarity
     * tier undersells it (a Huge of a common pet is a 1-in-millions pull
     * wearing a common's colour).
     */
    private boolean isSpecial(PackRollService.RollResult roll, Rarity rarity) {
        return roll.oneIn() >= SPECIAL_ONE_IN
                || (rarity != null && rarity.sortOrder() >= SPECIAL_SORT_ORDER);
    }

    /** The rarity's own colour as packed RGB, for the glowing outline - white if it has none. */
    private int glowColorFor(Rarity rarity) {
        if (rarity == null) {
            return 0xFFFFFF;
        }
        try {
            return Integer.parseInt(rarity.colorHex().replace("#", ""), 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private void trackHatch(HatchContext ctx) {
        Player player = ctx.player();
        if (!isLive(ctx)) {
            ctx.trackingTaskHolder()[0].cancel();
            return;
        }
        Location[] slots = hatchSlotLocations(player, ctx.count());
        RarePop pop = ctx.pop()[0];
        for (int i = 0; i < ctx.count(); i++) {
            if (pop == null || i != pop.slot) {
                PacketEntityManager.teleportEntity(player, ctx.itemIds()[i], slots[i]);
            }
            PacketEntityManager.teleportEntity(player, ctx.textIds()[i], slots[i].clone().add(0, ctx.labelOffsets()[i], 0));
        }
        if (pop != null) {
            advancePop(ctx, pop, slots);
        }
    }

    // ------------------------------------------------------------------
    // The rare pull: shake, pop into the player's face, land back
    // ------------------------------------------------------------------

    /** Ticks the rare pet rattles in its slot, growing, before it launches. */
    private static final int POP_SHAKE_TICKS = 10;
    /** Ticks it takes to fly from its slot into the player's view. */
    private static final int POP_IN_TICKS = 5;
    /** Ticks it hangs in front of the player's face, spinning. */
    private static final int POP_HOLD_TICKS = 16;
    /** Ticks it takes to drop back into its slot. */
    private static final int POP_OUT_TICKS = 6;
    private static final int POP_TOTAL_TICKS = POP_SHAKE_TICKS + POP_IN_TICKS + POP_HOLD_TICKS + POP_OUT_TICKS;
    /** How far in front of the eyes it hangs - close enough to fill a good chunk of the screen. */
    private static final double POP_DISTANCE = 1.15;
    /** How tall it renders while in the player's face, whatever its model - about half a block at arm's length. */
    private static final double POP_VISIBLE_BLOCKS = 0.55;
    private static final float POP_SHAKE_DEGREES = 22f;

    /** The special pull worth the pop - the rarest one if there are several - or -1 if the batch has none. */
    private int rarePopSlot(HatchContext ctx) {
        int best = -1;
        for (int slot = 0; slot < ctx.count(); slot++) {
            PackRollService.RollResult roll = ctx.rolls().get(slot);
            if (!isSpecial(roll, rarityRegistry.get().find(roll.item().rarityId()).orElse(null))) {
                continue;
            }
            if (best == -1 || roll.oneIn() > ctx.rolls().get(best).oneIn()) {
                best = slot;
            }
        }
        return best;
    }

    /**
     * One tick of the rare pull's moment. Driven by the hatch's own
     * tracking task rather than scheduled steps, so the pet follows the
     * player's head every tick of it - it is IN their face even if they
     * turn - and a replaced hatch stops it dead with everything else.
     * <ol>
     *   <li><b>Shake</b> - it rattles hard in its slot and swells, with a
     *       rising chime: something is happening.</li>
     *   <li><b>Pop</b> - it launches into the middle of the player's view,
     *       with the title card and the whole sound stack on the same
     *       tick.</li>
     *   <li><b>Hold</b> - it hangs there, spinning and breathing, long
     *       enough to see what it is and not so long it is in the way.</li>
     *   <li><b>Land</b> - it drops back into its slot at its glowing
     *       special size, and only now shows its name label.</li>
     * </ol>
     */
    private void advancePop(HatchContext ctx, RarePop pop, Location[] slots) {
        Player player = ctx.player();
        int entityId = ctx.itemIds()[pop.slot];
        int t = pop.elapsed++;
        Location slot = slots[pop.slot];
        Location face = facePoint(player);
        float growScale = pop.restScale * 1.25f;

        Location at;
        float scale;
        float pitch = 0f;
        float yaw;
        if (t < POP_SHAKE_TICKS) {
            at = slot;
            scale = pop.restScale + (growScale - pop.restScale) * t / (float) POP_SHAKE_TICKS;
            boolean left = (t / 2) % 2 == 0;
            yaw = facingYawTowardPlayer(slot, player) + (left ? -POP_SHAKE_DEGREES : POP_SHAKE_DEGREES);
            pitch = left ? -10f : 10f;
            if (t % 2 == 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.8f + t * 0.1f);
            }
        } else if (t < POP_SHAKE_TICKS + POP_IN_TICKS) {
            if (t == POP_SHAKE_TICKS) {
                celebrate(player, pop, face);
            }
            double k = easeOut((t - POP_SHAKE_TICKS + 1) / (double) POP_IN_TICKS);
            at = lerp(slot, face, k);
            scale = (float) (growScale + (pop.popScale - growScale) * k);
            yaw = facingYawTowardPlayer(at, player);
        } else if (t < POP_SHAKE_TICKS + POP_IN_TICKS + POP_HOLD_TICKS) {
            int held = t - POP_SHAKE_TICKS - POP_IN_TICKS;
            at = face;
            scale = (float) (pop.popScale * (1.0 + 0.05 * Math.sin(held * 0.7)));
            yaw = facingYawTowardPlayer(face, player) + held * 12f;
            if (held % 5 == 0) {
                player.spawnParticle(Particle.END_ROD, face, 6, 0.35, 0.35, 0.35, 0.02);
            }
        } else if (t < POP_TOTAL_TICKS) {
            double k = easeOut((t - (POP_TOTAL_TICKS - POP_OUT_TICKS) + 1) / (double) POP_OUT_TICKS);
            at = lerp(face, slot, k);
            scale = (float) (pop.popScale + (pop.restScale - pop.popScale) * k);
            yaw = facingYawTowardPlayer(at, player);
        } else {
            // Landed: settle into its slot for good and show its name.
            ItemDisplayManager.setInterpolation(player, entityId, 0, 2, 2);
            ItemDisplayManager.setScale(player, entityId, pop.restScale, pop.restScale, pop.restScale);
            ItemDisplayManager.setRotation(player, entityId, 0f, facingYawTowardPlayer(slot, player));
            PacketEntityManager.teleportEntity(player, entityId, slot);
            TextDisplayManager.setText(player, ctx.textIds()[pop.slot], pop.label);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.8f, 1.4f);
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, slot, 20, 0.3, 0.3, 0.3, 0.1);
            ctx.pop()[0] = null;
            return;
        }
        ItemDisplayManager.setInterpolation(player, entityId, 0, 1, 1);
        ItemDisplayManager.setScale(player, entityId, scale, scale, scale);
        ItemDisplayManager.setRotation(player, entityId, pitch, yaw);
        PacketEntityManager.teleportEntity(player, entityId, at);
    }

    /**
     * The launch: a title card in the pull's own colour, and every sound
     * that says "you got something" at once - fanfare, level-up, a
     * firework - plus a burst around the pet as it arrives.
     */
    private void celebrate(Player player, RarePop pop, Location face) {
        ItemDefinition item = pop.roll.item();
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String hex = rarity != null ? rarity.colorHex() : "#FFD700";
        String headline = item.huge() ? "HUGE" : rarity != null
                ? Formatting.stripLeadingColorCodes(rarity.displayName()).toUpperCase(Locale.ROOT) : "RARE";
        player.showTitle(Title.title(
                Text.parse("<" + hex + "><bold>✦ " + headline + " ✦</bold></" + hex + ">"),
                Text.parse("<white><name></white> <gray>(1 in <n>)</gray>",
                        Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(item.displayName())),
                        Placeholder.unparsed("n", Formatting.format((double) pop.roll.oneIn()))),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(300))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.9f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.2f);
        player.spawnParticle(Particle.TOTEM_OF_UNDYING, face, 45, 0.5, 0.5, 0.5, 0.35);
        player.spawnParticle(Particle.FIREWORK, face, 25, 0.4, 0.4, 0.4, 0.12);
    }

    /** Dead centre of the player's view, {@link #POP_DISTANCE} out - pitch included, so looking down still puts it in front of them. */
    private static Location facePoint(Player player) {
        Location eye = player.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(POP_DISTANCE));
    }

    private static Location lerp(Location from, Location to, double k) {
        return new Location(from.getWorld(),
                from.getX() + (to.getX() - from.getX()) * k,
                from.getY() + (to.getY() - from.getY()) * k,
                from.getZ() + (to.getZ() - from.getZ()) * k);
    }

    /** Fast then settling - a pop, not a slide. */
    private static double easeOut(double k) {
        double clamped = Math.max(0.0, Math.min(1.0, k));
        return 1.0 - (1.0 - clamped) * (1.0 - clamped);
    }

    /** The scheduled end of a hatch - a no-op if a newer one already replaced it. */
    private void despawnHatch(HatchContext ctx) {
        if (activeHatches.get(ctx.playerId()) != ctx) {
            return;
        }
        clearHatch(ctx.playerId());
    }

    /** Takes whatever hatch is on screen for this player off it, immediately. */
    private void clearHatch(UUID playerId) {
        HatchContext ctx = activeHatches.remove(playerId);
        if (ctx == null) {
            return;
        }
        BukkitTask tracking = ctx.trackingTaskHolder()[0];
        if (tracking != null) {
            tracking.cancel();
        }
        Player player = ctx.player();
        if (player.isOnline()) {
            PacketEntityManager.beginBundle(player);
            for (int i = 0; i < ctx.count(); i++) {
                PacketEntityManager.destroyEntity(player, ctx.itemIds()[i]);
                PacketEntityManager.destroyEntity(player, ctx.textIds()[i]);
            }
            PacketEntityManager.endBundle(player);
        }
        RevealSuppressionRegistry.end(playerId);
    }

    /**
     * Two lines rather than the single reveal's three - a grid packs rows
     * close enough together that a third line visibly collides with the row
     * below it.
     */
    private Component hatchSlotLabel(PackRollService.RollResult roll) {
        ItemDefinition item = roll.item();
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String colorHex = rarity != null ? rarity.colorHex() : "#FFFFFF";
        Component nameLine = Text.parse("<" + colorHex + "><bold><name></bold>",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(item.displayName())));
        Component oddsLine = Text.parse("<gray>(1 in <n>)</gray>",
                Placeholder.unparsed("n", Formatting.format((double) roll.oneIn())));
        return nameLine.append(Component.newline()).append(oddsLine);
    }

    /**
     * The grid in front of the player, sized to how many eggs are in it.
     * <p>
     * A fixed layout cannot serve both ends of the range: what reads well
     * for one egg buries a 24x hatch's bottom row in the floor and fills
     * the whole screen. So the batch decides its own scale, spacing and
     * distance, and - the part that actually broke in game - the grid grows
     * UPWARD from a fixed bottom edge just below eye level rather than
     * being centred on the eye. Four rows centred on the eye puts the
     * bottom row nearly two metres down, which is underground.
     */
    private Location[] hatchSlotLocations(Player player, int count) {
        float scale = eggScaleFor(count);
        double spacing = slotSpacing(count);
        int columns = columnsFor(count);
        int rows = (count + columns - 1) / columns;

        // Only as far back as the widest row actually needs. Roughly 1.4
        // blocks of width fit per block of distance at a default FOV, so
        // that is the distance at which the grid exactly fills the screen -
        // any further and it is just smaller for no reason. A single egg
        // falls back to the close-up distance rather than being shoved out
        // to arm's length by a formula meant for a wall of them.
        double gridWidth = (columns - 1) * spacing + scale;
        double forward = Math.max(FORWARD_DISTANCE, gridWidth / 1.4);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().setY(0);
        Vector forwardUnit = direction.lengthSquared() < 1.0e-6 ? new Vector(0, 0, 1) : direction.normalize();
        Vector right = new Vector(-forwardUnit.getZ(), 0, forwardUnit.getX()).normalize();
        Location anchor = eye.clone().add(forwardUnit.clone().multiply(forward));

        Location[] slots = new Location[count];
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;
            int inThisRow = Math.min(columns, count - row * columns);
            double xOffset = (col - (inThisRow - 1) / 2.0) * spacing;
            // Row 0 sits at the top; the LAST row sits just below eye level,
            // which is the one thing that keeps the whole grid off the floor
            // however many rows it has.
            double yOffset = (rows - 1 - row) * spacing - 0.35;
            slots[i] = anchor.clone().add(right.clone().multiply(xOffset)).add(0, yOffset, 0);
        }
        return slots;
    }

    /**
     * Wider than tall, deliberately. A square grid (the obvious
     * {@code ceil(sqrt(n))}) is the worst shape for a 16:9 screen: 24 eggs
     * five-by-five stands taller than the vertical field of view even
     * though there is width to spare either side. Biasing toward columns
     * spends the screen's real estate where the screen actually has it.
     */
    private int columnsFor(int count) {
        return Math.min(HATCH_COLUMNS, Math.max(1, (int) Math.ceil(Math.sqrt(count * 1.6))));
    }

    /** Smaller eggs for a bigger clutch - 24 at the single-egg size is a wall of shell with the player inside it - but only as small as the row width actually demands. */
    private float eggScaleFor(int count) {
        if (count <= 3) {
            return 0.85f;
        }
        if (count <= 8) {
            return 0.78f;
        }
        return count <= 15 ? 0.7f : 0.62f;
    }

    private Tier tierFor(Rarity rarity) {
        if (rarity == null) {
            return Tier.STANDARD;
        }
        if (rarity.sortOrder() >= 5) {
            return Tier.JACKPOT;
        }
        if (rarity.sortOrder() >= 3) {
            return Tier.BIG;
        }
        return Tier.STANDARD;
    }

    /**
     * {@code baseDelayTicks}/{@code rampTicks} are chosen so the WHOLE reel
     * (pre-roll pause + every step's delay) lands around 2 seconds for the
     * common case (STANDARD), with BIG/JACKPOT taking noticeably longer to
     * build suspense proportional to how rare the result actually is.
     */
    private TierConfig configFor(Tier tier) {
        return switch (tier) {
            case STANDARD -> new TierConfig(10, 15, 2.0, 3.0, Sound.UI_BUTTON_CLICK, 1.0f, Particle.HEART, 3);
            case BIG -> new TierConfig(14, 25, 2.0, 5.0, Sound.ENTITY_PLAYER_LEVELUP, 1.3f, Particle.TOTEM_OF_UNDYING, 20);
            case JACKPOT -> new TierConfig(18, 40, 2.0, 7.0, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, Particle.TOTEM_OF_UNDYING, 60);
        };
    }
}
