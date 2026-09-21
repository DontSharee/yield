package me.dontshare.yieldevents.data;

import java.time.LocalDate;

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
 * @param currencyName  what the event's currency is called, e.g. "Candy"
 * @param dropChance    chance per cube killed that it pays out at all
 * @param dropMin/Max   how much it pays when it does, inclusive
 * @param eggId         the pack id its station sells while it runs
 * @param eggPrice      how much of the currency one hatch costs
 */
public record SeasonalEvent(String id, String displayName, String color, LocalDate start, LocalDate end,
                             String currencyName, double dropChance, int dropMin, int dropMax,
                             String eggId, long eggPrice) {

    /** Whether this event is running on {@code today} - both ends inclusive, so a one-day event is possible. */
    public boolean isActiveOn(LocalDate today) {
        return !today.isBefore(start) && !today.isAfter(end);
    }

    /** Days left including today, or 0 once it is over. */
    public long daysRemaining(LocalDate today) {
        return Math.max(0, today.until(end).getDays() + (today.isAfter(end) ? 0 : 1));
    }
}
