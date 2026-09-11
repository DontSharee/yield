package me.dontshare.yieldpacks.shop;

/** The rotating shop's own settings - how often (in wall-clock time) it rotates. Every pack always has a slot; only its stock count varies - see ShopStockService. */
public record ShopConfig(long resetIntervalMillis, long openCooldownMillis) {
}
