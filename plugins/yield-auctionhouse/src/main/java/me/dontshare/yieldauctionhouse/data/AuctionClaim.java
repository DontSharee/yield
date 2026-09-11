package me.dontshare.yieldauctionhouse.data;

import java.math.BigInteger;
import java.util.UUID;

/**
 * One pending delivery sitting in a player's Collection Box - either a
 * currency payout (proceeds from a sale) or an item (a returned/cancelled
 * listing, or a buyer's own purchase) - never both. See
 * {@code AuctionService}'s own Javadoc for why EVERYTHING is delivered this
 * way instead of directly.
 */
public record AuctionClaim(
        UUID id,
        UUID ownerId,
        ClaimReason reason,
        AuctionCurrency currency,
        BigInteger amount,
        String serializedItem,
        long createdAtMillis
) {
    public boolean isCurrency() {
        return currency != null;
    }
}
