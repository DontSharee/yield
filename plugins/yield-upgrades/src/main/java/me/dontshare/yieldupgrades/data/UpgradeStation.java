package me.dontshare.yieldupgrades.data;

import org.bukkit.Location;

/**
 * One physical upgrade station - ties an {@link UpgradeType} to a zone with
 * a level cap specific to THIS station, at a real world location. The
 * player's own level in {@code type} is global (see
 * {@code PackPlayerProfile#getUpgradeLevels}) and shared across every
 * station of that type; {@code cap} only gates how far this particular
 * station lets them push it.
 * <p>
 * The four entity ids are reserved once, at content-load time, and reused
 * for this station's whole server lifetime - never re-allocated per viewer,
 * exactly like the tutorial NPC's single shared entity id (see
 * {@code UpgradeStationDisplay}).
 */
public record UpgradeStation(String zoneId, UpgradeType type, int cap, Location location,
                              int hitboxEntityId, int buttonEntityId, int wallEntityId, int textEntityId) {
}
