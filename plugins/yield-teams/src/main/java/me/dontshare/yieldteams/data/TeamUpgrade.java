package me.dontshare.yieldteams.data;

import org.bukkit.Material;

import java.math.BigInteger;
import java.util.List;

/** One team-upgrade line - {@code costs.get(level)} is the trophy cost to go from {@code level} to {@code level + 1}; {@code costs.size()} is the max level. */
public record TeamUpgrade(String id, String displayName, Material icon, TeamUpgradeType type,
                           double valuePerLevel, List<BigInteger> costs) {

    public int maxLevel() {
        return costs.size();
    }
}
