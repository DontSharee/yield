package me.dontshare.yieldevents.data;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * One seasonal event: a window of dates, a currency only it pays out, and
 * an egg only it sells.
 * <p>
 * Everything an event needs is already somewhere else in the codebase - the
 * egg is an ordinary pack in packs.yml (so it gets the hatch animation, the
 * odds screens and the Huge chase for nothing), and its station is an
 * ordinary station in pack-stations.yml (so it gets the big dragon egg, the
 * smack-to-hatch and the auto-hatch loop for nothing). What this record
 * adds is only what is genuinely seasonal: when it runs, what it pays, and
 * what that buys.
 *
 * @param currencyName  what the event's currency is called, e.g. "Candy Corn"
 * @param dropChance    chance per cube killed that it pays out at all
 * @param dropMin/Max   how much it pays when it does, inclusive
 * @param eggId         the pack id its station sells while it runs
 * @param eggPrice      how much of the currency one hatch costs
 * @param zoneId        the zone whose cubes pay the currency, or null for
 *                      "anywhere" - see EventCurrencyListener
 * @param petCandyBonus how much extra currency each of the event's own pets
 *                      earns while equipped, as a fraction
 * @param shop          what the currency buys other than eggs - see
 *                      {@link EventShopEntry}
 */
public record SeasonalEvent(String id, String displayName, String color, LocalDate start, LocalDate end,
                             String currencyName, double dropChance, int dropMin, int dropMax,
                             String eggId, long eggPrice, String zoneId,
                             Map<String, Double> petCandyBonus, List<EventQuest> quests,
                             List<EventShopEntry> shop) {

    /**
     * How much more candy this player's equipped pets earn them, as a
     * multiplier - 1.0 with none of the event's pets equipped.
     * <p>
     * The event's own pets boost the event's own currency and nothing else,
     * which is what makes them worth equipping DURING the event and a
     * trophy after it. A pet that boosted coins would either be worthless
     * next to a zone pet or have to out-scale one, and an event is not the
     * place to renegotiate the damage ladder.
     */
    public double candyMultiplierFor(Collection<String> equippedItemIds) {
        double bonus = 0.0;
        for (String itemId : equippedItemIds) {
            bonus += petCandyBonus.getOrDefault(itemId, 0.0);
        }
        return 1.0 + bonus;
    }

    /**
     * This year's run of the event - {@code halloween_2026} - which is what
     * quest progress, quest claims and shop stock are keyed by.
     * <p>
     * The id alone is not enough, because the id is reused: next October is
     * set up by changing this event's dates, not by writing a new one. Keyed
     * by id, a returning player would arrive at Halloween 2027 with every
     * quest already claimed, last year's hatches already counted and the
     * shop's pity stock already spent - an event with nothing in it for the
     * people most likely to come back. Anchored on the START year so an
     * event that runs across New Year is still one season.
     * <p>
     * The currency balance deliberately stays keyed by {@link #id()}: that
     * one is meant to carry over (see EventProfile).
     */
    public String seasonId() {
        return id + "_" + start.getYear();
    }

    /** Whether this event is running on {@code today} - both ends inclusive, so a one-day event is possible. */
    public boolean isActiveOn(LocalDate today) {
        return !today.isBefore(start) && !today.isAfter(end);
    }

    /** Days left including today, or 0 once it is over. */
    public long daysRemaining(LocalDate today) {
        // DAYS.between, not Period#getDays - that is only the days part of a
        // months-and-days period, so a six-week event read as two weeks.
        return Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, end) + 1);
    }
}
