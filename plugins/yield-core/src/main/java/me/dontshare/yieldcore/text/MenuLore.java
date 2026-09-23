package me.dontshare.yieldcore.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The standard lore layout for GUI items server-wide. Every item's lore
 * starts with a small-caps category label (via {@link Formatting#fancyFont})
 * in dark gray, then (if given) a blank line and a short description. Clickable buttons
 * (closing a GUI, changing sort, opening another screen, etc.) get a
 * "[CLICK]" name suffix and end with an underlined call to action; plain
 * informational/display items just
 * end with "│ Label: value" data lines instead (accent tick, gray label,
 * coloured value - see {@link #progress} for progress values); a two-action
 * item (e.g. a pet icon - left-click does one thing, right-click another)
 * uses {@link #dualAction} instead of either.
 */
public final class MenuLore {

    public static final String SEPARATOR = "│";

    /** The standard accent color used across almost every GUI - a handful of screens (e.g. StoreGui's gold) intentionally use their own instead. */
    public static final String ACCENT = "<#4BD9FF>";

    private MenuLore() {
    }

    /** {@code <accentHex>&l<NAME> &7[click]} (small-caps) - the standard name for a clickable button. */
    public static String buttonName(String accentHex, String name) {
        return accentHex + "&l" + name + " &7[" + Formatting.fancyFont("click") + "]";
    }

    /** {@code <color>&l<NAME>} - the standard name for a plain (non-clickable) informational item. */
    public static String infoName(String color, String name) {
        return color + "&l" + name;
    }

    /**
     * Lore for a clickable button: category, description, then an underlined
     * call to action.
     *
     * @param category      short label shown small-caps dark gray, e.g. "navigation"
     * @param description   pre-formatted description lines (leading space, mixed &f/&7) - pass an empty list to skip straight to the action lines
     * @param accentHex     this button's accent color, e.g. "&lt;#4BD9FF&gt;"
     * @param callToAction  the underlined closing line's text, e.g. "Click to View Category"
     */
    public static List<String> button(String category, List<String> description, String accentHex,
                                       String callToAction) {
        return button(category, description, accentHex, List.of(), callToAction);
    }

    /**
     * Same as {@link #button(String, List, String, String)}, with extra
     * accent-ticked stat lines (e.g. "Packages: 4x") inserted between the
     * description and the call to action - pass an empty list for the
     * common case with no extra lines.
     */
    public static List<String> button(String category, List<String> description, String accentHex,
                                       List<String> extraLines, String callToAction) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        if (!description.isEmpty()) {
            lore.addAll(description);
            lore.add("");
        }
        addDataLines(lore, accentHex, extraLines);
        lore.add("");
        lore.add(accentHex + "&n" + callToAction);
        return lore;
    }

    /**
     * Lore for a plain informational/display item: category, description,
     * then accent-colored data lines.
     *
     * @param category    short label shown small-caps dark gray, e.g. "pet"
     * @param description pre-formatted description lines (leading space, mixed &f/&7) - pass an empty list to skip straight to the data lines
     * @param accentHex   this item's accent color, e.g. "&lt;#4BD9FF&gt;"
     * @param dataLines   pre-formatted "&7Label: &fvalue" lines, each prefixed with the separator - an empty string entry becomes a plain blank-line group separator instead
     */
    public static List<String> info(String category, List<String> description, String accentHex,
                                     List<String> dataLines) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        if (!description.isEmpty()) {
            lore.addAll(description);
            lore.add("");
        }
        addDataLines(lore, accentHex, dataLines);
        return lore;
    }

    /**
     * Lore for a purchasable item (a store rank, a lootbox, etc.): category,
     * description, then accent-ticked data lines (odds, perks, whatever the
     * item needs - blank entries group them without a tick), then an
     * underlined call to action - unlike {@link #button}, there's no
     * Action/Usage pair, since "buy this" doesn't need one spelled out.
     *
     * @param dataLines pre-formatted lines, each prefixed with the separator - an empty string entry becomes a plain blank-line group separator instead
     */
    public static List<String> purchase(String category, List<String> description, String accentHex,
                                         List<String> dataLines, String callToAction) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        if (!description.isEmpty()) {
            lore.addAll(description);
            lore.add("");
        }
        addDataLines(lore, accentHex, dataLines);
        lore.add("");
        lore.add(accentHex + "&n" + callToAction);
        return lore;
    }

    /**
     * The tick carries the accent; the text after it always starts gray, so
     * a "Label: &fvalue" line reads as a gray label and a coloured value no
     * matter which screen built it. A line that opens with its own colour
     * code still overrides the gray.
     */
    private static void addDataLines(List<String> lore, String accentHex, List<String> dataLines) {
        for (String line : dataLines) {
            lore.add(line.isEmpty() ? "" : accentHex + SEPARATOR + " &7" + line);
        }
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

    /** {@code &8<small-caps category>} - dark gray, small-caps, matching every other administrative/category label server-wide. */
    private static String categoryLine(String category) {
        return "&8" + Formatting.fancyFont(category);
    }

    /**
     * Lore for an item with two distinct click actions instead of one (e.g.
     * a pet icon - left-click equips, right-click deletes): category,
     * description, then two plain gray instruction lines - no Action/Usage
     * pair, no underlined call to action, since there isn't a single one.
     *
     * @param category        short label shown small-caps dark gray, e.g. "pet"
     * @param description     pre-formatted description lines - pass an empty list to skip straight to the two action lines
     * @param leftClickText   e.g. "Left-Click to Equip"
     * @param rightClickText  e.g. "Right-Click to Delete"
     */
    public static List<String> dualAction(String category, List<String> description,
                                           String leftClickText, String rightClickText) {
        List<String> lore = new ArrayList<>();
        lore.add(categoryLine(category));
        lore.add("");
        if (!description.isEmpty()) {
            lore.addAll(description);
            lore.add("");
        }
        lore.add("&7" + leftClickText);
        lore.add("&7" + rightClickText);
        return lore;
    }
}
