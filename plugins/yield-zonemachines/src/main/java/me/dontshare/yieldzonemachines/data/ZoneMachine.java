package me.dontshare.yieldzonemachines.data;

import org.bukkit.Location;

/**
 * One physical machine, "smacked" (left-click/attack, matching the pack
 * stations' own convention rather than the upgrade stations' right-click -
 * see {@code ZoneMachineDisplay}) to trigger its {@link ZoneMachineType}'s
 * effect. Gated by that zone's own unlock state, same as a pack station.
 * <p>
 * The four entity ids are reserved once, at content-load time, and reused
 * for this machine's whole server lifetime - same convention every other
 * physical station in this codebase already uses.
 */
public record ZoneMachine(String zoneId, ZoneMachineType type, Location location,
                           int hitboxEntityId, int buttonEntityId, int wallEntityId, int textEntityId) {
}
