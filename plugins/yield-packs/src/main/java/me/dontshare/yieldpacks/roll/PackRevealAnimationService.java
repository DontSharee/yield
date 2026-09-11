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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                                BukkitTask[] trackingTaskHolder) {
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

    public void playReel(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                          long rollCountBeforeThisRoll) {
        // 1.0 = the default pace below (~2s for a common/uncommon/rare
        // result). Not yet wired to a real upgrade - threaded through now so
        // a future "faster reveal" upgrade just has to pass a smaller value
        // here (as low as ~0.05 for a near-instant 0.1s reveal) rather than
        // needing this whole class touched again.
        playReel(player, pack, finalItem, luckMultiplier, rollCountBeforeThisRoll, 1.0);
    }

    public void playReel(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                          long rollCountBeforeThisRoll, double speedMultiplier) {
        start(player, pack, finalItem, luckMultiplier, rollCountBeforeThisRoll, speedMultiplier, true);
    }

    /**
     * Same cycling odds/pity-bar/sounds as {@link #playReel} but with NO
     * in-world item/text strip - for a player with the roll animation
     * setting disabled, who still wants the action-bar reveal itself (just
     * not the heavy entities in front of them).
     */
    public void playCompactReel(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                                 long rollCountBeforeThisRoll) {
        start(player, pack, finalItem, luckMultiplier, rollCountBeforeThisRoll, 1.0, false);
    }

    private void start(Player player, PackDefinition pack, ItemDefinition finalItem, double luckMultiplier,
                        long rollCountBeforeThisRoll, double speedMultiplier, boolean spawnEntities) {
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
                rollCountBeforeThisRoll, Math.max(0.05, speedMultiplier), spawnEntities, trackingTaskHolder);
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
