package me.dontshare.yieldtrade.currency;

import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** The three balances on {@link PackPlayerProfile} a note can be minted from. */
public enum TradeCurrency {

    COINS("Coins", "<#FFC64B>", PackPlayerProfile::getCoins, PackPlayerProfile::setCoins),
    DIAMONDS("Diamonds", "<#4BD9FF>", PackPlayerProfile::getDiamonds, PackPlayerProfile::setDiamonds),
    CREDITS("Credits", "<#C77DFF>", PackPlayerProfile::getCredits, PackPlayerProfile::setCredits);

    private final String displayName;
    private final String colorTag;
    private final Function<PackPlayerProfile, BigInteger> getter;
    private final BiConsumer<PackPlayerProfile, BigInteger> setter;

    TradeCurrency(String displayName, String colorTag,
                  Function<PackPlayerProfile, BigInteger> getter,
                  BiConsumer<PackPlayerProfile, BigInteger> setter) {
        this.displayName = displayName;
        this.colorTag = colorTag;
        this.getter = getter;
        this.setter = setter;
    }

    public String displayName() {
        return displayName;
    }

    public String colorTag() {
        return colorTag;
    }

    public BigInteger balanceOf(PackPlayerProfile profile) {
        return getter.apply(profile);
    }

    /** False (and nothing written) if {@code profile} can't cover {@code amount}. */
    public boolean deduct(PackPlayerProfile profile, BigInteger amount) {
        BigInteger balance = getter.apply(profile);
        if (balance.compareTo(amount) < 0) {
            return false;
        }
        setter.accept(profile, balance.subtract(amount));
        return true;
    }

    public void credit(PackPlayerProfile profile, BigInteger amount) {
        setter.accept(profile, getter.apply(profile).add(amount));
    }

    public static Optional<TradeCurrency> fromId(String raw) {
        for (TradeCurrency currency : values()) {
            if (currency.name().equalsIgnoreCase(raw)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
