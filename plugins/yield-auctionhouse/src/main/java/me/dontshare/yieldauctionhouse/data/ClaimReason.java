package me.dontshare.yieldauctionhouse.data;

public enum ClaimReason {
    /** The seller's cut (after tax) of a completed sale. */
    SALE_PROCEEDS,
    /** A listing was cancelled or expired unsold - the item itself, returned to the seller. */
    LISTING_RETURNED,
    /** A buyer's successful purchase - the item itself, delivered to the buyer. */
    PURCHASE
}
