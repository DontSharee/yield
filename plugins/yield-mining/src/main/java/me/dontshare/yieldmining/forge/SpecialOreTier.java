package me.dontshare.yieldmining.forge;

/** One rarity tier a mined ore can roll into as a "Special Ore" - see ForgeContentLoader. */
public record SpecialOreTier(String id, String displayName, int oneIn, double multiplierMin, double multiplierMax) {
}
