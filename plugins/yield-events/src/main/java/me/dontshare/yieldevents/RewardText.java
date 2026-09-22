package me.dontshare.yieldevents;

import java.util.Locale;

/**
 * Turns a reward command into words a player can read.
 * <p>
 * Deliberately crude: it reads the command a server owner actually wrote
 * rather than a second "label" field they would have to keep in sync with
 * it, so a reward can never advertise one thing and hand over another.
 * Shared by the quest screen and the shop because both hand out rewards
 * the same way and a shelf that described them differently from a quest
 * would read as two different games.
 */
public final class RewardText {

    private RewardText() {
    }

    public static String describeCommand(String command) {
        String[] parts = command.trim().split("\\s+");
        // "admin potions give <player> <stat> <multiplier> <duration>"
        if (parts.length >= 7 && parts[0].equals("admin") && parts[1].equals("potions")) {
            return "x" + parts[5] + " " + prettify(parts[4]) + " Potion";
        }
        // "admin pets give <player> <pet> <fusion> <amount>"
        if (parts.length >= 7 && parts[0].equals("admin") && parts[1].equals("pets")) {
            return prettify(parts[4].replaceFirst("^event_", ""));
        }
        return command;
    }

    /** snake_case or SCREAMING_CASE to Title Case. */
    public static String prettify(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
