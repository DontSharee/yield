package me.dontshare.yieldpackstations.data;

import org.bukkit.Location;

/**
 * One physical egg station, "smacked" (left-click/attack) to hatch what it
 * holds - see {@code PackStationService}. Either:
 * <ul>
 *   <li>a <b>zone station</b>: {@code zoneId}/{@code fixedPackId} both set,
 *       gated by that zone's own unlock state, {@code dynamicPackKey}
 *       null;</li>
 *   <li>a <b>dynamic station</b>: the opposite - {@code zoneId}/{@code
 *       fixedPackId} null and a {@code dynamicPackKey} naming whoever owns
 *       its contents, re-resolved live on every render.</li>
 * </ul>
 * The black market is a dynamic station ({@code "black_market"}, its egg
 * fixed by config) and so is a seasonal event's own station ({@code
 * "event"}, its egg whichever event is running today, and nothing at all
 * when none is). Making that a KEY rather than a boolean is what lets a
 * plugin yield-packstations has never heard of own a station: see
 * {@code PackStationService#registerDynamicPack}.
 * <p>
 * The four entity ids are reserved once, at content-load time, and reused
 * for this station's whole server lifetime - same convention as
 * yield-upgrades' own {@code UpgradeStation}.
 */
public record PackStation(String zoneId, String fixedPackId, String dynamicPackKey, Location location,
                           int hitboxEntityId, int buttonEntityId, int wallEntityId, int textEntityId) {

    /** Whether someone else owns what this station holds - true for the black market and for an event station. */
    public boolean isDynamic() {
        return dynamicPackKey != null;
    }
}
