package me.dontshare.yieldblocktree.data;

import me.dontshare.yieldcore.text.Formatting;

import java.util.Locale;
import java.util.function.DoubleFunction;

/**
 * The one-of-a-kind rewards at the top of a block's tree - a {@code perk}
 * effect names one of these in its {@code data}, and its {@code value} is
 * the number the perk runs on (a chance, a bonus, a count - each constant
 * says which). BlockPerkService is where each one actually happens.
 * <p>
 * Each perk exists on exactly one tree. What it says in a menu is generated
 * from its value, so blocktree.yml can retune any of them without the
 * wording going stale.
 */
public enum BlockPerk {

    /** value: kills between avalanches. */
    AVALANCHE("Avalanche", "&f", v -> "&7Every &f" + whole(v) + "th &7kill knocks &c50% HP &7off all your cubes"),
    /** value: extra tap damage (1.0 = +100%). */
    FROSTBITE("Frostbite", "&b", v -> "&7Your taps deal &c+" + pct(v) + " &7damage"),
    /** value: faster retargeting (0.5 = +50%). */
    SLIPSTREAM("Slipstream", "&b", v -> "&7Pets switch targets &e" + pct(v) + " &7faster"),
    /** value: extra cubes alive at once. */
    QUARRY("Quarry", "&7", v -> "&a+" + whole(v) + " &7cubes alive at once in every zone"),
    /** value: extra crit chance. */
    TEMPERED_EDGE("Tempered Edge", "&f", v -> "&c+" + pct(v) + " &7pet crit chance"),
    /** value: chance per kill. */
    WILDFIRE("Wildfire", "&c", v -> "&e" + pct(v) + " &7of kills spread fire: &c25% HP &7off your nearest cube"),
    /** value: chance per kill. */
    MIDAS_TOUCH("Midas Touch", "&6", v -> "&e" + pct(v) + " &7of kills pay &610x coins"),
    /** value: extra coins from giant cubes (1.0 = +100%). */
    MOLTEN_CORE("Molten Core", "&6", v -> "&7Giant cubes pay &6+" + pct(v) + " &7coins"),
    /** value: damage bonus while standing still. */
    STONEWALL("Stonewall", "&8", v -> "&c+" + pct(v) + " &7pet damage while you stand still"),
    /** value: chance per kill. */
    SHADOW_CLONE("Shadow Clone", "&8", v -> "&e" + pct(v) + " &7of kills pay out &ftwice"),
    /** value: damage per distinct block broken in the last minute. */
    RESONANCE("Resonance", "&f", v -> "&c+" + pct(v) + " &7damage per different block broken in the last minute"),
    /** value: chance per kill. */
    GEODE("Geode", "&d", v -> "&e" + pct(v) + " &7of kills crack open a random &dShard"),
    /** value: chance per tap. */
    STALACTITE("Stalactite", "&6", v -> "&e" + pct(v) + " &7of taps strike for &c10x &7damage"),
    /** value: damage per unlocked zone. */
    DEEP_ROOTS("Deep Roots", "&8", v -> "&c+" + pct(v) + " &7pet damage for every zone you've unlocked"),
    /** value: chance per diamond payout. */
    MOTHERLODE("Motherlode", "&b", v -> "&e" + pct(v) + " &7of diamond drops are &b5x"),
    /** value: Enchant Market discount. */
    HAGGLER("Haggler", "&a", v -> "&7Enchant Market prices &a-" + pct(v)),
    /** value: extra enchant slots. */
    ENCHANTERS_GIFT("Enchanter's Gift", "&a", v -> "&d+" + whole(v) + " &7enchant slot"),
    /** value: extra golden/diamond cube chance. */
    WEEPING_FORTUNE("Weeping Fortune", "&5", v -> "&e+" + pct(v) + " &7chance for &6Golden &7and &bDiamond &7cubes"),
    /** value: extra spawn weight for big safes and boss blocks (1.0 = twice as often). */
    RELIC_HUNTER("Relic Hunter", "&4", v -> "&aBig Safes &7and &cBoss Blocks &7spawn &e" + times(1 + v) + " &7as often"),
    /** value: share of the respawn delay removed. */
    SECOND_WIND("Second Wind", "&5", v -> "&7Your cubes respawn &e" + pct(v) + " &7faster"),
    /** value: chance per kill. */
    ARCHAEOLOGIST("Archaeologist", "&8", v -> "&e" + pct(v) + " &7of kills dig up an &dEnchant Book"),
    /** value: extra pet XP from every kill (1.0 = +100%). */
    SCHOLAR("Scholar", "&7", v -> "&7Equipped pets gain &a+" + pct(v) + " &7XP from every kill"),
    /** value: extra equip slots. */
    EXTRA_HAND("Extra Hand", "&e", v -> "&a+" + whole(v) + " &7pet equip slot"),
    /** value: chance per tap. */
    ECHO("Echo", "&d", v -> "&e" + pct(v) + " &7of taps echo onto &fevery &7cube of yours"),
    /** value: chance per kill. */
    STASH("Stash", "&5", v -> "&e" + pct(v) + " &7of kills stash a free &fzone egg"),
    /** value: coin bonus shared with everyone near you. */
    BEACON_AURA("Beacon Aura", "&b", v -> "&6+" + pct(v) + " &7coins for you and every player within &f16 &7blocks"),
    /** value: extra golden/diamond cube bonus (1.0 = the bonus doubled). */
    RADIANCE("Radiance", "&e", v -> "&6Golden &7and &bDiamond &7cubes pay &e" + times(1 + v) + " &7their bonus"),
    /** value: how many times over the first kill each minute pays. */
    HIGH_TIDE("High Tide", "&3", v -> "&7Your first kill each minute pays &6" + times(v) + " &7coins"),
    /** value: chance per kill. */
    TRICK_OR_TREAT("Trick or Treat", "&6", v -> "&e" + pct(v) + " &7of kills: &6treat &7(5x coins) or &ctrick &7(+50% damage, 10s)"),
    /** value: chance per kill. */
    WISP("Wisp", "&6", v -> "&e" + pct(v) + " &7of kills summon a wisp: &6+100% &7coins for &f30s"),
    /** value: most bonus damage it can reach. */
    SOUL_LINK("Soul Link", "&3", v -> "&c+1% &7damage per &f1K &7lifetime kills &8(max &c" + pct(v) + "&8)"),
    /** value: extra treasure chest spawn weight (1.0 = twice as often). */
    TREASURE_SENSE("Treasure Sense", "&6", v -> "&6Treasure chests &7spawn &e" + times(1 + v) + " &7as often"),
    /** value: chance a big safe drops a book. */
    SAFECRACKER("Safecracker", "&a", v -> "&aBig Safes &7drop an &dEnchant Book &8(Rare+) &7" + (v >= 1 ? "every time" : pct(v) + " of the time")),
    /** value: damage bonus after a boss block kill. */
    WARLORD("Warlord", "&c", v -> "&7Killing a &cBoss Block &7gives &c+" + pct(v) + " &7damage for &f5 min");

    private final String displayName;
    private final String color;
    private final DoubleFunction<String> describe;

    BlockPerk(String displayName, String color, DoubleFunction<String> describe) {
        this.displayName = displayName;
        this.color = color;
        this.describe = describe;
    }

    public String displayName() {
        return displayName;
    }

    /** {@code &6&lMidas Touch} - how the perk's name is shown wherever it appears. */
    public String title() {
        return color + "&l" + displayName;
    }

    /** What the perk does at {@code value}, as one generated line. */
    public String describe(double value) {
        return describe.apply(value);
    }

    /** Parses blocktree.yml's kebab-case ids ("midas-touch"). */
    public static BlockPerk parse(String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static String pct(double v) {
        return Formatting.format(v * 100) + "%";
    }

    private static String whole(double v) {
        return String.valueOf(Math.round(v));
    }

    private static String times(double v) {
        return Formatting.format(v) + "x";
    }
}
