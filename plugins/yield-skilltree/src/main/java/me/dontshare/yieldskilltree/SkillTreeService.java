package me.dontshare.yieldskilltree;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldskilltree.data.SkillTreeProfile;
import me.dontshare.yieldskilltree.data.NodeType;
import me.dontshare.yieldskilltree.data.SkillNode;
import me.dontshare.yieldskilltree.data.SkillTree;
import me.dontshare.yieldskilltree.event.SkillNodeBoughtEvent;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Formula evaluation + purchase/auto-buy logic, shared by every tree -
 * mirrors the reference Skript script's Skilltree_returnEquation/
 * Skilltree_buyLevel/Skilltree_autoBuy, translated to Java + exp4j.
 */
public final class SkillTreeService {

    public enum BuyResult {
        MAXED, LOCKED, NO_FUNDS, BOUGHT, UNKNOWN
    }

    private final Supplier<Map<String, SkillTree>> trees;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** This plugin's own node levels. The pack store above stays for the currency a node costs. */
    private final PlayerDataStore<SkillTreeProfile> skillStore;

    /**
     * A formula's result only depends on (nodeId, kind, level) - never on
     * the player or anything else live - so it's safe to cache indefinitely
     * until {@link #clearCache}. Auto-buy and the GUI's lore both re-check
     * the same (node, level) many times per click/open; without this every
     * one of those re-parses the formula string via exp4j from scratch.
     */
    private final Map<String, Double> formulaCache = new ConcurrentHashMap<>();

    public SkillTreeService(Supplier<Map<String, SkillTree>> trees, PlayerDataStore<PackPlayerProfile> store,
                             PlayerDataStore<SkillTreeProfile> skillStore) {
        this.trees = trees;
        this.store = store;
        this.skillStore = skillStore;
    }

    /** Call after a content reload - a formula string may have changed under the same (nodeId, level) key. */
    public void clearCache() {
        formulaCache.clear();
    }

    /** Takes the pack profile purely as a player handle - the levels themselves are this plugin's own. */
    public int levelOf(PackPlayerProfile profile, String nodeId) {
        return skillStore.getOrCreate(profile.getPlayerId()).getLevels().getOrDefault(nodeId, 0);
    }

    /** Every id in {@code node.requires()} must be owned (level >= 1) first. */
    public boolean isUnlocked(PackPlayerProfile profile, SkillNode node) {
        for (String requiredId : node.requires()) {
            if (levelOf(profile, requiredId) < 1) {
                return false;
            }
        }
        return true;
    }

    public double costFor(SkillNode node, int level) {
        return evaluate(node.id(), "cost", node.costFormula(), level, node.maxLevel());
    }

    public double valueFor(SkillNode node, int level) {
        return evaluate(node.id(), "value", node.valueFormula(), level, node.maxLevel());
    }

    private double evaluate(String nodeId, String kind, String formula, int level, int maxLevel) {
        String cacheKey = nodeId + "|" + kind + "|" + level;
        Double cached = formulaCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String substituted = formula.replace("<level>", String.valueOf(level)).replace("<max_level>", String.valueOf(maxLevel));
        double result = new ExpressionBuilder(substituted).build().evaluate();
        formulaCache.put(cacheKey, result);
        return result;
    }

    public BuyResult buyLevel(Player player, String treeId, String nodeId) {
        SkillTree tree = trees.get().get(treeId);
        SkillNode node = tree != null ? tree.nodes().get(nodeId) : null;
        if (tree == null || node == null) {
            return BuyResult.UNKNOWN;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (!isUnlocked(profile, node)) {
            return BuyResult.LOCKED;
        }
        int level = levelOf(profile, nodeId);
        if (level >= node.maxLevel()) {
            return BuyResult.MAXED;
        }
        double cost = costFor(node, level + 1);
        BigInteger balance = tree.currency().balanceOf(profile);
        if (balance.compareTo(toBigInteger(cost)) < 0) {
            return BuyResult.NO_FUNDS;
        }
        tree.currency().setBalance(profile, balance.subtract(toBigInteger(cost)));
        skillStore.getOrCreate(player.getUniqueId()).getLevels().merge(nodeId, 1, Integer::sum);
        store.save(player.getUniqueId());
        skillStore.save(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new SkillNodeBoughtEvent(player, treeId, nodeId, level + 1));
        return BuyResult.BOUGHT;
    }

    /**
     * exp4j's Expression#evaluate() has no arbitrary-precision path - a
     * formula-derived cost stays a double (an inherent library ceiling, not
     * worth working around for a level-bounded cost curve). This is the one
     * conversion boundary where that double meets the now-BigInteger balance.
     */
    private static BigInteger toBigInteger(double d) {
        return BigDecimal.valueOf(d).setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }

    /**
     * Buys every level the player can afford in {@code treeId}, up to
     * {@code cap} levels total, maxing what's already owned before
     * unlocking anything new - one forward pass, not a repeated rescan:
     * since a node's cost depends only on its own level and currency only
     * decreases, nothing unaffordable on this pass (when the balance was
     * highest) can ever become affordable later in the same run.
     */
    public int autoBuy(Player player, String treeId, int cap) {
        SkillTree tree = trees.get().get(treeId);
        if (tree == null) {
            return 0;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int bought = 0;

        for (SkillNode node : tree.nodes().values()) {
            if (bought >= cap) {
                break;
            }
            if (levelOf(profile, node.id()) <= 0) {
                continue;
            }
            bought += buyUpToAndNotify(player, profile, tree, treeId, node, cap - bought);
        }
        if (bought < cap) {
            for (SkillNode node : tree.nodes().values()) {
                if (bought >= cap) {
                    break;
                }
                if (levelOf(profile, node.id()) > 0 || !isUnlocked(profile, node)) {
                    continue;
                }
                bought += buyUpToAndNotify(player, profile, tree, treeId, node, cap - bought);
            }
        }
        if (bought > 0) {
            store.save(player.getUniqueId());
            skillStore.save(player.getUniqueId());
        }
        return bought;
    }

    /** {@link #buyUpTo} plus one {@link SkillNodeBoughtEvent} for this node if it actually gained any levels - not once per level, since auto-buy can roll several at once and callers only care that the node was purchased into this pass. */
    private int buyUpToAndNotify(Player player, PackPlayerProfile profile, SkillTree tree, String treeId, SkillNode node, int remainingCap) {
        int bought = buyUpTo(profile, tree, node, remainingCap);
        if (bought > 0) {
            Bukkit.getPluginManager().callEvent(new SkillNodeBoughtEvent(player, treeId, node.id(), levelOf(profile, node.id())));
        }
        return bought;
    }

    private int buyUpTo(PackPlayerProfile profile, SkillTree tree, SkillNode node, int remainingCap) {
        int bought = 0;
        while (bought < remainingCap) {
            int level = levelOf(profile, node.id());
            if (level >= node.maxLevel()) {
                break;
            }
            double cost = costFor(node, level + 1);
            BigInteger balance = tree.currency().balanceOf(profile);
            BigInteger bigCost = toBigInteger(cost);
            if (balance.compareTo(bigCost) < 0) {
                break;
            }
            tree.currency().setBalance(profile, balance.subtract(bigCost));
            skillStore.getOrCreate(profile.getPlayerId()).getLevels().merge(node.id(), 1, Integer::sum);
            bought++;
        }
        return bought;
    }

    /** Sums {@code valueFor} across every owned node of {@code type} in every tree - see {@link NodeType}'s Javadoc for how each type's total is consumed. */
    public double totalFor(PackPlayerProfile profile, NodeType type) {
        double total = 0;
        for (SkillTree tree : trees.get().values()) {
            for (SkillNode node : tree.nodes().values()) {
                if (node.type() != type) {
                    continue;
                }
                int level = levelOf(profile, node.id());
                if (level > 0) {
                    total += valueFor(node, level);
                }
            }
        }
        return total;
    }

    public double totalFor(Player player, NodeType type) {
        return totalFor(store.getOrCreate(player.getUniqueId()), type);
    }
}
