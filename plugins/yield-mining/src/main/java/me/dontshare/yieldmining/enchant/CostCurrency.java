package me.dontshare.yieldmining.enchant;

import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.math.BigInteger;

/** Which of the two existing currencies a pickaxe enchant's levels are bought with. */
public enum CostCurrency {
    GEMS {
        @Override
        public BigInteger balanceOf(PackPlayerProfile profile) {
            return profile.getGems();
        }

        @Override
        public void setBalance(PackPlayerProfile profile, BigInteger balance) {
            profile.setGems(balance);
        }
    },
    COINS {
        @Override
        public BigInteger balanceOf(PackPlayerProfile profile) {
            return profile.getCoins();
        }

        @Override
        public void setBalance(PackPlayerProfile profile, BigInteger balance) {
            profile.setCoins(balance);
        }
    };

    public abstract BigInteger balanceOf(PackPlayerProfile profile);

    public abstract void setBalance(PackPlayerProfile profile, BigInteger balance);
}
