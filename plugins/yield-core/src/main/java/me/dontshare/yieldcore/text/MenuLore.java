package me.dontshare.yieldcore.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The house style for every menu item's name and lore, in one place so a
 * screen can't drift from it:
 * <pre>
 *   <b>Gem Finder</b> Enchant           name: the thing bold, its kind plain
 *   ᴇɴᴄʜᴀɴᴛ                         small-caps category, dark gray
 *
 *   Increases the chance of...     optional short explanation, gray
 *
 *   <b>Information</b>                    section header, accent
 *    | Level: 0 / 10               gray tick + gray label, coloured value
 *    | Cost: 5k Stars
 *
 *   <b>CLICK HERE</b>                     call to action, accent
 *   Click to upgrade enchant!
 * </pre>
 * A purchase ends in {@code → Click to BUY!} instead, and an item the
 * player carries uses {@link #RULE} lines around its stat block.
 */
public final class MenuLore {

    /** The tick at the start of every data line. */
    public static final String SEPARATOR = "|";

    /** A strikethrough rule - the divider above and below a carried item's stat block. */
    public static final String RULE = "&8&m                              ";

    /** The standard accent color used across almost every GUI - a handful of screens (e.g. StoreGui's gold) intentionally use their own instead. */
    public static final String ACCENT = "<#4BD9FF>";

    private static final java.util.regex.Pattern LEADING_COLOR =
            java.util.regex.Pattern.compile("(?i)(&[0-9a-fl-or]|<#[0-9a-f]{6}>|<(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|bold)>)");
    private static final java.util.regex.Pattern ROMAN = java.util.regex.Pattern.compile("[IVXLCM]+");

    private MenuLore() {
    }

    /** The name of anything clickable - see {@link #name}. */
    public static String buttonName(String accentHex, String name) {
        return name(accentHex, name);
    }

    /** The name of a plain display item - same style as a button, since the lore says whether it can be clicked. */
    public static String infoName(String color, String name) {
        return name(color, name);
    }

    /**
     * {@code <b>Gem Finder</b> Enchant}: every word but the last bold, the
     * last one plain - the name first, what kind of thing it is after. A
     * single word is bold on its own. ALL-CAPS words from older screens
     * are title-cased ("AUTO ATTACK" -> "Auto Attack"), except the ones
     * that are meant to shout: ON/OFF, roman numerals, and anything with a
     * digit or a colour code in it.
     */
    public static String name(String color, String raw) {
        String name = raw.trim();
        // A name that brings its own leading colour ("&6Candy Pumpkin", a
        // rarity-coloured pet) keeps it - and has to be pulled out front,
        // since a legacy colour code after the bold would reset it.
        java.util.regex.Matcher lead = LEADING_COLOR.matcher(name);
        while (lead.lookingAt()) {
            String code = lead.group().toLowerCase(java.util.Locale.ROOT);
            if (!code.equals("&l") && !code.equals("&r") && !code.equals("<bold>") && !code.matches("&[m-o]")) {
                color = lead.group();
            }
            name = name.substring(lead.end()).trim();
            lead = LEADING_COLOR.matcher(name);
        }
        name = titleCase(name);
        int cut = name.lastIndexOf(' ');
        if (cut <= 0) {
            return color + "&l" + name;
        }
        return color + "&l" + name.substring(0, cut) + "&r" + color + name.substring(cut);
    }

    private static String titleCase(String raw) {
        StringBuilder out = new StringBuilder();
        for (String word : raw.split(" ", -1)) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(shouldTitleCase(word) ? word.charAt(0) + word.substring(1).toLowerCase(java.util.Locale.ROOT) : word);
        }
        return out.toString();
    }

    private static boolean shouldTitleCase(String word) {
        String letters = word.replaceAll("[^A-Za-z]", "");
        if (letters.length() < 2 || !letters.equals(letters.toUpperCase(java.util.Locale.ROOT))) {
            return false;
        }
        if (word.contains("&") || word.contains("<") || word.matches(".*\\d.*")) {
            return false;
        }
        return !(letters.equals("ON") || letters.equals("OFF") || ROMAN.matcher(letters).matches());
    }

    /**
     * Lore for a clickable button: category, description, then the call to
     * action.
     *
     * @param category      short label shown small-caps dark gray, e.g. "navigation"
     * @param description   short explanation lines - pass an empty list for none
     * @param accentHex     this button's accent color, e.g. "&lt;#4BD9FF&gt;"
     * @param callToAction  what a click does, e.g. "Click to upgrade enchant"
     */
    public static List<String> button(String category, List<String> description, String accentHex,
                                       String callToAction) {
        return button(category, description, accentHex, List.of(), callToAction);
    }

    /** Same as {@link #button(String, List, String, String)}, with an Information block of data lines before the call to action. */
    public static List<String> button(String category, List<String> description, String accentHex,
                                       List<String> extraLines, String callToAction) {
        List<String> lore = head(category, description);
        addInformation(lore, accentHex, extraLines);
        lore.add(accentHex + "&lCLICK HERE");
        lore.add("&f" + sentence(callToAction));
        return lore;
    }

    /**
     * Lore for a plain informational/display item: category, description,
     * then an Information block.
     *
     * @param dataLines "Label: &amp;fvalue" lines - each gets a gray tick and a gray label; an empty string is a blank spacer line
     */
    public static List<String> info(String category, List<String> description, String accentHex,
                                     List<String> dataLines) {
        List<String> lore = head(category, description);
        addInformation(lore, accentHex, dataLines);
        trimTrailingBlank(lore);
        return lore;
    }

    /**
     * Lore for something bought with a click (a store rank, a zone, a
     * weapon): category, description, Information block, then
     * {@code → Click to BUY!}.
     */
    public static List<String> purchase(String category, List<String> description, String accentHex,
                                         List<String> dataLines, String callToAction) {
        List<String> lore = head(category, description);
        addInformation(lore, accentHex, dataLines);
        lore.add(arrowAction(accentHex, callToAction));
        return lore;
    }

    /**
     * Lore for a thing that is an item first - a weapon in your hotbar, the
     * next upgrade in a menu: category, then its stats between two
     * {@link #RULE}s (indented "Label: value", no ticks), then one closing
     * line - a dark-gray hint for a carried item, or {@link #arrowAction}
     * for one you can buy.
     */
    public static List<String> item(String category, List<String> stats, String closingLine) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        lore.add(RULE);
        for (String stat : stats) {
            lore.add(stat.isEmpty() ? "" : " &7" + stat);
        }
        lore.add(RULE);
        if (closingLine != null && !closingLine.isEmpty()) {
            lore.add("");
            lore.add(closingLine);
        }
        return lore;
    }

    /** {@code → Click to UPGRADE!} - the verb after "Click to" shouts in the accent colour. */
    public static String arrowAction(String accentHex, String callToAction) {
        String text = callToAction.trim();
        String lead = "Click to ";
        if (text.regionMatches(true, 0, lead, 0, lead.length())) {
            String verb = text.substring(lead.length()).toUpperCase(java.util.Locale.ROOT);
            return accentHex + "→ &fClick to " + accentHex + "&l" + verb + "!";
        }
        return accentHex + "→ &f" + sentence(text);
    }

    /**
     * The data lines as an Information block - header, then {@code  | Label: value}
     * lines, then a blank line. Nothing at all when there are no lines.
     */
    private static void addInformation(List<String> lore, String accentHex, List<String> dataLines) {
        if (dataLines.stream().allMatch(String::isEmpty)) {
            return;
        }
        lore.add(accentHex + "&lInformation");
        for (String line : dataLines) {
            lore.add(line.isEmpty() ? "" : dataLine(line));
        }
        lore.add("");
    }

    /**
     * One {@code  | Label: value} line: the tick and the text after it start
     * gray, so a "Label: &amp;fvalue" line reads as a gray label and a coloured
     * value whichever screen built it. A line that opens with its own
     * colour code still overrides the gray.
     */
    public static String dataLine(String line) {
        return " &7" + SEPARATOR + " &7" + line;
    }

    /**
     * The one way a "how far along" value is written server-wide:
     * {@code &a<current> &8/ &c<needed>} - green for what you have, red for
     * what it takes. Use as the value half of a data line, e.g.
     * {@code "Progress: " + MenuLore.progress(12, 100)}.
     */
    public static String progress(double current, double needed) {
        return "&a" + Formatting.format(current) + " &8/ &c" + Formatting.format(needed);
    }

    /** Category line, a blank line, and the description (flush left, gray) followed by a blank line when there is one. */
    private static List<String> head(String category, List<String> description) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        boolean any = false;
        for (String line : description) {
            String flush = line.startsWith(" ") ? line.substring(1) : line;
            lore.add(flush.startsWith("&") || flush.startsWith("<") ? flush : "&7" + flush);
            any = true;
        }
        if (any) {
            lore.add("");
        }
        return lore;
    }

    private static void trimTrailingBlank(List<String> lore) {
        while (!lore.isEmpty() && lore.get(lore.size() - 1).isEmpty()) {
            lore.remove(lore.size() - 1);
        }
    }

    /** "Click to View Members" -> "Click to view members!" - the reference's plain-sentence call to action. */
    private static String sentence(String text) {
        String trimmed = text.trim();
        StringBuilder out = new StringBuilder();
        String[] words = trimmed.split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (i > 0) {
                out.append(' ');
                boolean shout = word.length() > 1 && word.equals(word.toUpperCase(java.util.Locale.ROOT));
                out.append(shout ? word : word.toLowerCase(java.util.Locale.ROOT));
            } else {
                out.append(word);
            }
        }
        char last = out.isEmpty() ? '.' : out.charAt(out.length() - 1);
        if (last != '!' && last != '.' && last != '?') {
            out.append('!');
        }
        return out.toString();
    }

    /** {@code &8<small-caps category>} - dark gray, small-caps, matching every other administrative/category label server-wide. */
    private static String categoryLine(String category) {
        return "&8" + Formatting.fancyFont(category);
    }

    /**
     * Lore for an item with two distinct click actions instead of one (e.g.
     * a pet icon - left-click equips, right-click deletes): category,
     * description, then the two actions.
     *
     * @param leftClickText   e.g. "Left-Click to Equip"
     * @param rightClickText  e.g. "Right-Click to Delete"
     */
    public static List<String> dualAction(String category, List<String> description,
                                           String leftClickText, String rightClickText) {
        return dualAction(category, description, ACCENT, List.of(), leftClickText, rightClickText);
    }

    /** {@link #dualAction(String, List, String, String)} with an Information block before the two actions. */
    public static List<String> dualAction(String category, List<String> description, String accentHex, List<String> dataLines,
                                           String leftClickText, String rightClickText) {
        List<String> lore = head(category, description);
        addInformation(lore, accentHex, dataLines);
        lore.add(accentHex + "&lCLICK HERE");
        lore.add("&f" + leftClickText);
        lore.add("&f" + rightClickText);
        return lore;
    }
}
