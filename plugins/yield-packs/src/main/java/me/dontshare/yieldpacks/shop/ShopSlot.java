package me.dontshare.yieldpacks.shop;

import me.dontshare.yieldpacks.data.PackDefinition;

/** One rolled shop slot for the current rotation cycle - a pack and how many units are available this cycle. */
public record ShopSlot(PackDefinition pack, int stockCount) {
}
