package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.data.PackDefinition;
import java.math.BigInteger;
import me.dontshare.yieldpacks.roll.PackRollService;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The single source of truth for "what can this pack actually give me, and
 * at what odds" - shared verbatim by the shop icon, the storage icon and
 * the open dialog so a player never sees two different numbers for the
 * same pack.
 * <p>
 * Everything here is the player's REAL, current odds, not the raw table:
 * <ul>
 *   <li>Pool percentages come from {@link PackRollService#oddsFor} at this
 *       player's own luck, so investing in luck visibly moves the numbers
 *       (the old shop lore divided raw weights, which meant a maxed-luck
 *       player was shown a beginner's odds).</li>
 *   <li>The Huge line is luck-scaled the same way the roll itself is, and
 *       is omitted entirely for a pack whose pool has no Huge-eligible
 *       pets - it would be a lie there.</li>
 *   <li>Shiny is flat by design (see VariantConfig), so it reads the same
 *       everywhere, forever.</li>
 *   <li>The Exclusive Find line only appears once a player has actually
 *       earned that perk (see yield-blocktree); at 0% it isn't a mechanic
 *       they have.</li>
 * </ul>
 * The pool percentages deliberately ignore the Huge/Exclusive overrides
 * that can replace a roll after the fact. Both are far under a percent, so
 * folding them in would shift every displayed number by less than its own
 * rounding while making the three lines contradict each other.
 */
public final class PackOddsLore {

    private static final String ACCENT = "&b";

    private final PackRollService rollService;

    public PackOddsLore(PackRollService rollService) {
        this.rollService = rollService;
    }

    /** Legacy-coded lore lines: every pet in the pool at this player's luck, then the chase odds. No leading/trailing blank line - callers place their own. */
    public List<String> lines(PackDefinition pack, Player player) {
        List<String> lore = new ArrayList<>();
        double luck = rollService.displayLuckFor(player);
        rollService.oddsFor(pack, luck).stream()
                .sorted(Comparator.comparingDouble(PackRollService.WeightedOdds::probability).reversed())
                .forEach(odds -> lore.add(odds.item().displayName() + " &7(" + formatChance(odds.probability()) + ")"));

        List<String> chase = chaseLines(pack, player);
        if (!chase.isEmpty()) {
            lore.add("");
            lore.addAll(chase);
        }
        return lore;
    }

    /** Just the luck/Huge/Shiny/Exclusive block - the part that is about the player rather than the pack. */
    public List<String> chaseLines(PackDefinition pack, Player player) {
        List<String> lore = new ArrayList<>();
        double luck = rollService.displayLuckFor(player);
        if (luck > 1.0) {
            lore.add("&7Your Luck: " + ACCENT + "x" + formatMultiplier(luck));
        }
        PackRollService.ChaseOdds chase = rollService.chaseOddsFor(pack, player);
        if (chase.hugeChance() > 0) {
            lore.add("&6&l✦ HUGE &7(" + formatOdds(chase.hugeChance()) + ")");
        }
        if (chase.shinyChance() > 0) {
            lore.add("&f&l✦ Shiny &7(" + formatOdds(chase.shinyChance()) + ")");
        }
        if (chase.exclusiveFindChance() > 0) {
            lore.add("&d&l✦ Exclusive Find &7(" + formatOdds(chase.exclusiveFindChance()) + ")");
        }
        return lore;
    }

    /**
     * An egg's price in words - the one place that decision is made, so the
     * station sign, the catalog, the merchant and the hatch menu cannot
     * disagree about what something costs.
     * <p>
     * A credit-priced egg shows ONLY its credit price: the black market is
     * deliberately either/or (see {@link PackDefinition#creditPriced()}), and
     * printing "$0" beside it would read as a bug.
     */
    public static String costLine(PackDefinition egg, int count) {
        int units = Math.max(1, count);
        if (egg.creditPriced()) {
            return "&b" + Formatting.format(BigInteger.valueOf(egg.creditCost()).multiply(BigInteger.valueOf(units)))
                    + " credits";
        }
        if (egg.coinCost() <= 0 && egg.diamondCost() <= 0) {
            // Nothing here prices it, so something else does - a seasonal
            // event charges its own currency at its own station (see
            // yield-events). "$0" would read as free, which it is not.
            return "&7paid at its station";
        }
        String line = "&a$" + Formatting.format(BigInteger.valueOf(egg.coinCost()).multiply(BigInteger.valueOf(units)));
        if (egg.diamondCost() > 0) {
            line += " &8+ &b" + Formatting.format(BigInteger.valueOf(egg.diamondCost()).multiply(BigInteger.valueOf(units)))
                    + " diamonds";
        }
        return line;
    }

    /**
     * A percentage while that still means something, and "1 in N" once it
     * doesn't - the whole point of the chase lines is odds a player can
     * feel, and "0.0%" (which is what a Huge rounds to) is not one.
     */
    public static String formatChance(double probability) {
        if (probability <= 0) {
            return "never";
        }
        if (probability >= 0.1) {
            return Math.round(probability * 100) + "%";
        }
        if (probability >= 0.001) {
            return trimZeros(String.format(Locale.ROOT, "%.2f", probability * 100)) + "%";
        }
        return "1 in " + formatOneIn(Math.round(1.0 / probability));
    }

    /**
     * Always "1 in N", however likely it is - the chase lines are compared
     * against each other ("Huge 1 in 2,500, Shiny 1 in 120") and against
     * the same line at a different luck, and a format that flips to
     * percentages partway up the scale makes both comparisons work for the
     * reader instead of for itself.
     */
    public static String formatOdds(double probability) {
        return probability <= 0 ? "never" : "1 in " + formatOneIn(Math.round(1.0 / probability));
    }

    /** Exact digits ("1 in 2,500") while they stay readable, abbreviated ("1 in 4.2B") once they don't. */
    private static String formatOneIn(long oneIn) {
        return oneIn < 1_000_000
                ? Formatting.format(oneIn, Formatting.Style.SPACED)
                : Formatting.format(oneIn);
    }

    /** "1.5" / "12" - a luck multiplier reads better without a trailing ".0". */
    public static String formatMultiplier(double multiplier) {
        return multiplier % 1 == 0
                ? String.valueOf((long) multiplier)
                : trimZeros(String.format(Locale.ROOT, "%.2f", multiplier));
    }

    private static String trimZeros(String decimal) {
        if (!decimal.contains(".")) {
            return decimal;
        }
        String trimmed = decimal.replaceAll("0+$", "");
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
