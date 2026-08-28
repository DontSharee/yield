package me.dontshare.yieldcore.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The standard lore layout for GUI items server-wide. Every item's lore
 * starts with a small-caps category label (via {@link Formatting#fancyFont})
 * in dark gray, then a blank line, then a short description. Clickable
 * buttons (closing a GUI, changing sort, opening another screen, etc.) get a
 * "[ᴄʟɪᴄᴋ]" name suffix and end with accent-colored "Action"/"Usage" lines
 * plus an underlined call to action; plain informational/display items just
 * end with accent-colored "│ Label: value" data lines instead.
 */
public final class MenuLore {

    public static final String SEPARATOR = "│";

    private MenuLore() {
    }

    /** {@code <accentHex>&l<NAME> &7[ᴄʟɪᴄᴋ]} - the standard name for a clickable button. */
    public static String buttonName(String accentHex, String name) {
        return accentHex + "&l" + name + " &7[" + Formatting.fancyFont("click") + "]";
    }

    /** {@code <color>&l<NAME>} - the standard name for a plain (non-clickable) informational item. */
    public static String infoName(String color, String name) {
        return color + "&l" + name;
    }

    /**
     * Lore for a clickable button: category, description, then
     * accent-colored Action/Usage lines and an underlined call to action.
     *
     * @param category      short label shown small-caps in dark gray, e.g. "navigation"
     * @param description   one or more pre-formatted description lines (leading space, mixed &f/&7)
     * @param accentHex     this button's accent color, e.g. "&lt;#4BD9FF&gt;"
     * @param action        what clicking does, e.g. "View Suffixes"
     * @param usage         how to trigger it, e.g. "Click"
     * @param callToAction  the underlined closing line's text, e.g. "Click to View Category"
     */
    public static List<String> button(String category, List<String> description, String accentHex,
                                       String action, String usage, String callToAction) {
        List<String> lore = new ArrayList<>();
        lore.add("&8" + Formatting.fancyFont(category));
        lore.add("");
        lore.addAll(description);
        lore.add("");
        lore.add(accentHex + SEPARATOR + " &7Action: &f" + action);
        lore.add(accentHex + SEPARATOR + " &7Usage: &f" + usage);
        lore.add("");
        lore.add(accentHex + "&n" + callToAction);
        return lore;
    }

    /**
     * Lore for a plain informational/display item: category, description,
     * then accent-colored data lines.
     *
     * @param category    short label shown small-caps in dark gray, e.g. "pet"
     * @param description one or more pre-formatted description lines (leading space, mixed &f/&7)
     * @param accentHex   this item's accent color, e.g. "&lt;#4BD9FF&gt;"
     * @param dataLines   pre-formatted "&7Label: &fvalue" lines, each prefixed with the separator
     */
    public static List<String> info(String category, List<String> description, String accentHex,
                                     List<String> dataLines) {
        List<String> lore = new ArrayList<>();
        lore.add("&8" + Formatting.fancyFont(category));
        lore.add("");
        lore.addAll(description);
        lore.add("");
        for (String line : dataLines) {
            lore.add(accentHex + SEPARATOR + " " + line);
        }
        return lore;
    }
}
