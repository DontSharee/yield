package me.dontshare.yieldzonemachines.data;

import org.bukkit.Location;

/**
 * A physical particle-ring trigger - walking into it (not smacking) fires
 * its {@link WalkInTriggerType}'s effect. No packet entity ids at all,
 * unlike {@link ZoneMachine} - a ring is pure {@code Player#spawnParticle}
 * calls plus a proximity check, nothing persistent needs spawning/despawning
 * per viewer.
 */
public record WalkInTrigger(String zoneId, WalkInTriggerType type, Location center) {
}
