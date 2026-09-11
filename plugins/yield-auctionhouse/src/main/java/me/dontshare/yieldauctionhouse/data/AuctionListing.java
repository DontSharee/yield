package me.dontshare.yieldauctionhouse.data;

import java.math.BigInteger;
import java.util.UUID;

/**
 * One listing - literally whatever real {@link org.bukkit.inventory.ItemStack}
 * the seller had in their main hand at listing time, preserved byte-for-byte
 * (see {@code me.dontshare.yieldcore.item.ItemSerialization}), including a
 * withdrawn pet's own tag data. The auction house never needs to know or care
 * what the item actually is - a pet, a lootbox, a potion, or anything else -
 * it's all just bytes to it; whatever tagged that item is responsible for
 * recognizing it again once delivered.
 */
public record AuctionListing(
        UUID id,
        UUID sellerId,
        String sellerName,
        String serializedItem,
        AuctionCurrency currency,
        BigInteger price,
        ListingStatus status,
        long listedAtMillis,
        long expiresAtMillis,
        UUID buyerId
) {
}
