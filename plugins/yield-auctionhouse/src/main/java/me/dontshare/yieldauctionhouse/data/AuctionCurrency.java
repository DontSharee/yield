package me.dontshare.yieldauctionhouse.data;

/** Every listing is priced in exactly one of these - the seller's own choice at listing time. */
public enum AuctionCurrency {
    COINS,
    DIAMONDS,
    CREDITS;

    /**
     * {@link #valueOf}, but also accepts "GEMS" - the pre-rename name for
     * DIAMONDS. Listings/claims are hand-mapped to/from a raw Mongo
     * {@code Document} (no POJO codec involved), so a currency already
     * stored as the literal string "GEMS" before the Diamonds rename would
     * otherwise throw {@link IllegalArgumentException} the next time that
     * listing or claim is read back. Use this at every read site pulling
     * a currency out of a persisted document; {@link #valueOf} is still
     * correct for fresh user input (e.g. a command argument), which was
     * never stored under the old name.
     */
    public static AuctionCurrency fromStored(String raw) {
        return "GEMS".equals(raw) ? DIAMONDS : valueOf(raw);
    }
}
