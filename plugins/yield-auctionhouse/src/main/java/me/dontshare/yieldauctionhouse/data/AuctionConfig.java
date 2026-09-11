package me.dontshare.yieldauctionhouse.data;

/** Parsed auctionhouse.yml - see AuctionConfigLoader. */
public record AuctionConfig(double saleTaxPercent, long listingDurationMillis, long expirySweepIntervalTicks,
                             int maxActiveListingsPerPlayer, long minimumPrice, long browseRefreshIntervalTicks) {
}
