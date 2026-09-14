package me.dontshare.yieldpacks.chat;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.util.function.Function;

/**
 * The "[&lt;rebirths&gt;] " badge shown before a player's name in chat -
 * registered once from {@code YieldPacks#onEnable} via {@code core
 * .getChatFormatter().registerNameTagProvider(...)}. Both the bracket's
 * color and its symbol escalate with rebirth count: a two-tone HSB gradient
 * whose hue shifts every single rebirth (one full loop roughly every 25.6
 * of them), and a symbol that steps up every 50 rebirths past the first 50
 * - landing on a fixed pastel-gradient tier at 1000+, since an ever-cycling
 * hue stops reading as "special" once a player has seen the whole wheel
 * many times over. Yield has no standalone "player level" stat, so
 * prestiges (the closest existing second-tier progression counter) still
 * fills that role as a trailing roman-numeral suffix, same as before.
 */
public final class RebirthChatBadge implements Function<Player, Component> {

    // One flower/star shape every 50 rebirths past the first 50 - falls
    // back to a plain gold star if a very high band ever runs past the end
    // of this list (it won't in practice: 1000+ gets its own fixed tier
    // below well before that could happen).
    private static final String[] SYMBOLS = {
            "✧", "✩", "✫", "✬", "✭", "✮", "✯", "❁", "❃", "❋",
            "✵", "✶", "✷", "✸", "✹", "✴", "✳", "✲", "✱", "✰", "✪"
    };
    private static final int HUE_STEP = 10; // out of 256 - one full color-wheel loop every ~25.6 rebirths
    private static final int MAX_TIER_REBIRTHS = 1000;
    // Hand-picked pastel stops, not a sine-swept rainbow - a plain HSB
    // rainbow at high lightness dips through a saturated cyan/blue band
    // partway through its cycle, which reads as an ugly seam right in the
    // middle of what's supposed to be this game's single flashiest badge.
    private static final String[] PASTEL_GRADIENT = {
            "#FFADAD", "#FFD6A5", "#FDFFB6", "#CAFFBF", "#9BF6FF", "#A0C4FF", "#BDB2FF", "#FFC6FF"
    };

    private final PlayerDataStore<PackPlayerProfile> store;

    public RebirthChatBadge(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    @Override
    public Component apply(Player player) {
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return Component.empty();
        }
        String badge = prefixFor(profile.getRebirths());
        if (profile.getPrestiges() <= 0) {
            return Text.parse(badge + " ");
        }
        return Text.parse(badge + " <gray>•</gray> <white><prestige></white><gray> </gray>",
                Placeholder.unparsed("prestige", toRoman(profile.getPrestiges())));
    }

    /** The raw "[...]" badge string (MiniMessage tags, not yet parsed) for this rebirth count - call this directly (no {@link Player} needed) to use the same badge anywhere else that renders MiniMessage text, e.g. a scoreboard line. */
    public static String prefixFor(int rebirths) {
        if (rebirths >= MAX_TIER_REBIRTHS) {
            StringBuilder gradientTag = new StringBuilder("<gradient");
            for (String stop : PASTEL_GRADIENT) {
                gradientTag.append(':').append(stop);
            }
            gradientTag.append('>');
            return "<gray>[" + gradientTag + rebirths + "⭐</gradient><gray>]";
        }
        if (rebirths <= 0) {
            return "<#38B9C7>[<#66F0FF>0⭐<#38B9C7>]";
        }
        String symbol = symbolFor(rebirths);
        float hue = ((rebirths * HUE_STEP) % 256) / 256f;
        String bright = hsbHex(hue, 0.7f, 1.0f);
        String dim = hsbHex(hue, 0.7f, 0.75f);
        return "<" + dim + ">[<" + bright + ">" + rebirths + symbol + "<" + dim + ">]";
    }

    /** Bands: 1-25, 26-50, then every 50 up to 999 (1000+ never reaches here - see {@link #prefixFor}). */
    private static String symbolFor(int rebirths) {
        if (rebirths <= 25) {
            return SYMBOLS[0];
        }
        if (rebirths <= 50) {
            return SYMBOLS[1];
        }
        int index = (rebirths - 1) / 50 + 1;
        return index < SYMBOLS.length ? SYMBOLS[index] : "★";
    }

    private static String hsbHex(float hue, float saturation, float brightness) {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        return String.format("#%06X", rgb);
    }

    /** Standard subtractive-notation roman numerals - 0 renders as a literal "0" (roman numerals have no zero). */
    public static String toRoman(int number) {
        if (number <= 0) {
            return "0";
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        int remaining = number;
        for (int i = 0; i < values.length && remaining > 0; i++) {
            while (remaining >= values[i]) {
                result.append(symbols[i]);
                remaining -= values[i];
            }
        }
        return result.toString();
    }
}
