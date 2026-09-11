package me.dontshare.yieldskilltree.data;

import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.math.BigInteger;

/** Which balance a tree spends - see {@code SkillTree#currency()}. */
public enum Currency {
    COINS {
        @Override
        public BigInteger balanceOf(PackPlayerProfile profile) {
            return profile.getCoins();
        }

        @Override
        public void setBalance(PackPlayerProfile profile, BigInteger amount) {
            profile.setCoins(amount);
        }
    },
    PRESTIGE_POINTS {
        @Override
        public BigInteger balanceOf(PackPlayerProfile profile) {
            return profile.getPrestigePoints();
        }

        @Override
        public void setBalance(PackPlayerProfile profile, BigInteger amount) {
            profile.setPrestigePoints(amount);
        }
    };

    public abstract BigInteger balanceOf(PackPlayerProfile profile);

    public abstract void setBalance(PackPlayerProfile profile, BigInteger amount);
}
