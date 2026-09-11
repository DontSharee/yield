package me.dontshare.yieldzonemachines.display;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldzonemachines.data.WalkInTrigger;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The visual + proximity-detection layer for walk-in triggers - a spinning
 * particle ring you physically walk into (no smack, no hitbox entity at
 * all) opens the relevant GUI the instant you cross its radius. Unlike
 * {@code ZoneMachineDisplay}, this needs no packet entities whatsoever -
 * particles are transient per-viewer effects on their own, and the "did
 * they just walk in" check is a plain distance comparison against the
 * player's own real location.
 */
public final class WalkInTriggerDisplay {

    private static final double RING_RADIUS = 1.3;
    private static final int RING_POINTS = 20;
    private static final double ACTIVATION_RADIUS = 1.4;
    private static final double ACTIVATION_HEIGHT = 2.0;
    private static final double VIEW_DISTANCE_SQUARED = 32.0 * 32.0;
    private static final long TICK_INTERVAL = 4L;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;

    private volatile List<WalkInTrigger> triggers = List.of();
    private final Map<UUID, Set<WalkInTrigger>> insideByPlayer = new ConcurrentHashMap<>();
    private long tick;

    public WalkInTriggerDisplay(JavaPlugin plugin, YieldPacks packs, Supplier<Map<String, ZoneDefinition>> zones,
                                 ZoneLockService zoneLockService) {
        this.plugin = plugin;
        this.packs = packs;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /** A content reload swaps the trigger list wholesale - clear every player's "currently inside" tracking rather than trying to reconcile it against triggers that may no longer exist at all. */
    public void reload(List<WalkInTrigger> newTriggers) {
        triggers = newTriggers;
        insideByPlayer.clear();
    }

    private void tick() {
        tick += TICK_INTERVAL;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<WalkInTrigger> currentlyInside = insideByPlayer.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            for (WalkInTrigger trigger : triggers) {
                if (!viewer.getWorld().equals(trigger.center().getWorld())) {
                    continue;
                }
                if (trigger.center().distanceSquared(viewer.getLocation()) <= VIEW_DISTANCE_SQUARED) {
                    renderRing(viewer, trigger);
                }
                boolean inside = isInside(viewer.getLocation(), trigger.center());
                boolean wasInside = currentlyInside.contains(trigger);
                if (inside && !wasInside) {
                    currentlyInside.add(trigger);
                    handleEnter(viewer, trigger);
                } else if (!inside && wasInside) {
                    currentlyInside.remove(trigger);
                }
            }
        }
    }

    private boolean isInside(Location playerLoc, Location center) {
        double dx = playerLoc.getX() - center.getX();
        double dz = playerLoc.getZ() - center.getZ();
        return dx * dx + dz * dz <= ACTIVATION_RADIUS * ACTIVATION_RADIUS
                && Math.abs(playerLoc.getY() - center.getY()) <= ACTIVATION_HEIGHT;
    }

    private void renderRing(Player viewer, WalkInTrigger trigger) {
        Location center = trigger.center();
        // A slow, continuous spin (one full rotation every 5s) - purely
        // cosmetic, just makes the ring read as "alive" rather than a
        // static decal on the ground.
        double rotation = (tick % 100) / 100.0 * Math.PI * 2;
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = rotation + (Math.PI * 2 * i / RING_POINTS);
            double x = center.getX() + Math.cos(angle) * RING_RADIUS;
            double z = center.getZ() + Math.sin(angle) * RING_RADIUS;
            viewer.spawnParticle(Particle.DUST, x, center.getY() + 0.1, z, 1, 0, 0, 0, 0, dustFor(trigger, i));
        }
    }

    private Particle.DustOptions dustFor(WalkInTrigger trigger, int ringIndex) {
        return switch (trigger.type()) {
            case GOLDEN_FUSION -> new Particle.DustOptions(org.bukkit.Color.fromRGB(0xFFD700), 1.0f);
            case RAINBOW_FUSION -> {
                float hue = ((ringIndex + tick / 4f) % RING_POINTS) / (float) RING_POINTS;
                int rgb = java.awt.Color.HSBtoRGB(hue, 1f, 1f) & 0xFFFFFF;
                yield new Particle.DustOptions(org.bukkit.Color.fromRGB(rgb), 1.0f);
            }
            // FusionTier.DARK_MATTER's own darkest gradient stop - deliberately
            // near-black/deep-purple rather than the bright cyan Enchants
            // uses below, so the two never read as the same ring at a glance.
            case DARK_MATTER_FUSION -> new Particle.DustOptions(org.bukkit.Color.fromRGB(0x2B0B3F), 1.0f);
            // Matches MenuLore.ACCENT - the same cyan every Enchant Slot/book icon already uses.
            case ENCHANTS -> new Particle.DustOptions(org.bukkit.Color.fromRGB(0x4BD9FF), 1.0f);
        };
    }

    private void handleEnter(Player player, WalkInTrigger trigger) {
        ZoneDefinition zone = zones.get().get(trigger.zoneId());
        if (zone == null || !zoneLockService.isUnlocked(player, zone)) {
            player.sendMessage(Text.parse("<red>You haven't unlocked this zone.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        switch (trigger.type()) {
            case GOLDEN_FUSION -> packs.getFusionGui().open(player, FusionTier.GOLDEN);
            case RAINBOW_FUSION -> packs.getFusionGui().open(player, FusionTier.RAINBOW);
            case DARK_MATTER_FUSION -> packs.getFusionGui().open(player, FusionTier.DARK_MATTER);
            case ENCHANTS -> packs.getEnchantGui().open(player);
        }
    }
}
