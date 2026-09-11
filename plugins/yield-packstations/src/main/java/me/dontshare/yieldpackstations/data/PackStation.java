package me.dontshare.yieldpackstations.data;

import org.bukkit.Location;

/**
 * One physical pack-selling station, "smacked" (left-click/attack) to buy
 * one unit of its pack into storage - see {@code PackStationService}. Either
 * a zone station ({@code zoneId}/{@code fixedPackId} both set, gated by that
 * zone's own unlock state, {@code isBlackMarket} false) or the black
 * market's station (the opposite - {@code isBlackMarket} true,
 * {@code zoneId}/{@code fixedPackId} null, whichever pack is currently live
 * comes from {@code BlackMarketRotationService}, re-resolved live every
 * render).
 * <p>
 * The four entity ids are reserved once, at content-load time, and reused
 * for this station's whole server lifetime - same convention as
 * yield-upgrades' own {@code UpgradeStation}.
 */
public record PackStation(String zoneId, String fixedPackId, boolean isBlackMarket, Location location,
                           int hitboxEntityId, int buttonEntityId, int wallEntityId, int textEntityId) {
}
