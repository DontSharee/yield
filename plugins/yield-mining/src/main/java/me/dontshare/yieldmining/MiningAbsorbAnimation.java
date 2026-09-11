package me.dontshare.yieldmining;

import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * The client-sided "absorb" visual for a mined block - a small chunk that
 * spins and hovers in place for a beat (a little wind-up, not an instant
 * yeet), then gets pulled toward the breaking player's CURRENT eye location
 * with increasing speed (re-read every tick, so it keeps homing in if they
 * move), shrinking down to nothing right as it arrives. Per-player only,
 * like every other packet display in this codebase - nobody else can see
 * it.
 */
final class MiningAbsorbAnimation {

    private static final int HOLD_TICKS = 8;
    private static final int HOMING_TICKS = 22;

    private static final float HOLD_SCALE = 0.45f;
    private static final float END_SCALE = 0.05f;
    private static final double HOVER_BOB_HEIGHT = 0.08;

    private static final double HOLD_SPIN_RADIANS_PER_TICK = Math.toRadians(22);
    private static final double HOMING_SPIN_START_RADIANS_PER_TICK = Math.toRadians(18);
    private static final double HOMING_SPIN_END_RADIANS_PER_TICK = Math.toRadians(6);

    private static final double MIN_CATCH_UP = 0.05;
    private static final double MAX_CATCH_UP = 0.55;
    private static final double ARRIVAL_DISTANCE = 0.4;

    private MiningAbsorbAnimation() {
    }

    static void play(JavaPlugin plugin, Player player, Location blockLocation, Material material) {
        int entityId = PacketEntityManager.nextEntityId();
        Location anchor = blockLocation.clone().add(0.5, 0.5, 0.5);

        BlockDisplayManager.spawn(player, entityId, anchor);
        BlockDisplayManager.setBlockState(player, entityId, material);
        BlockDisplayManager.setTransformation(player, entityId, -HOLD_SCALE / 2f, HOLD_SCALE);

        new BukkitRunnable() {
            int tick = 0;
            double angle = 0;
            Location current = anchor.clone();

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (tick < HOLD_TICKS) {
                    // A short wind-up: hover and spin in place near the
                    // block before getting pulled toward the player.
                    double bob = Math.sin(tick * 0.9) * HOVER_BOB_HEIGHT;
                    Location hoverPos = anchor.clone().add(0, bob, 0);
                    angle += HOLD_SPIN_RADIANS_PER_TICK;

                    BlockDisplayManager.setInterpolation(player, entityId, 0, 2, 2);
                    PacketEntityManager.teleportEntity(player, entityId, hoverPos);
                    BlockDisplayManager.setYRotation(player, entityId, angle);

                    current = hoverPos;
                    tick++;
                    return;
                }

                int homingTick = tick - HOLD_TICKS;
                Location target = player.getEyeLocation();
                if (homingTick >= HOMING_TICKS || current.distance(target) <= ARRIVAL_DISTANCE) {
                    player.playSound(current, Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.8f);
                    player.spawnParticle(Particle.CRIT, current, 5, 0.1, 0.1, 0.1, 0.02);
                    PacketEntityManager.destroyEntity(player, entityId);
                    cancel();
                    return;
                }

                double progress = homingTick / (double) HOMING_TICKS;
                double catchUp = MIN_CATCH_UP + (MAX_CATCH_UP - MIN_CATCH_UP) * progress;
                Vector delta = target.toVector().subtract(current.toVector()).multiply(catchUp);
                current = current.clone().add(delta);

                float scale = (float) (HOLD_SCALE - (HOLD_SCALE - END_SCALE) * progress);
                double spinSpeed = HOMING_SPIN_START_RADIANS_PER_TICK
                        - (HOMING_SPIN_START_RADIANS_PER_TICK - HOMING_SPIN_END_RADIANS_PER_TICK) * progress;
                angle += spinSpeed;

                BlockDisplayManager.setInterpolation(player, entityId, 0, 2, 2);
                PacketEntityManager.teleportEntity(player, entityId, current);
                BlockDisplayManager.setTransformation(player, entityId, -scale / 2f, scale);
                BlockDisplayManager.setYRotation(player, entityId, angle);

                tick++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
