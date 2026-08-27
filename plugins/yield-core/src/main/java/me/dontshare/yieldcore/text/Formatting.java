package me.dontshare.yieldcore.text;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Number/text formatting helpers ({@code format}, {@code spaced},
 * {@code unformat}, {@code fancyFont}).
 */
public final class Formatting {

    /** {@link #format(double, Style, boolean)} suffixes, index 0 = "K" (i.e. divisor 1000^1). */
    private static final String[] SUFFIXES = {
            "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc", "Ud", "Td",
            "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod", "Vg", "Uvg", "Dvg", "Tvg", "Qavg",
            "Qivg", "Sxvg", "Spvg", "Ocvg", "Ocvg", "Nov", "Tt", "Unt", "Dtt", "Ttt", "Qut", "Qat", "Sst"
    };

    /** {@link #unformat}'s lookup list - "0" is a placeholder so K aligns with index 1, matching one 1000x step. */
    private static final String[] UNFORMAT_SUFFIXES = {
            "0", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc", "Ud", "Td",
            "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod", "Vg", "Uvg", "Dvg", "Tvg", "Qavg",
            "Qivg", "Sxvg", "Spvg", "Ocvg", "Ocvg", "Nov", "Tt", "Unt", "Dtt", "Ttt", "Qut", "Qat", "Sst"
    };

    private static final String NORMAL_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String TINY_LETTERS = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    public enum Style {
        /** e.g. 1500 -> "1.5K" */
        ABBREVIATED,
        /** e.g. 1500 -> "1,500" */
        SPACED
    }

    private Formatting() {
    }

    public static String format(double n) {
        return format(n, Style.ABBREVIATED, false);
    }

    public static String format(double n, Style style) {
        return format(n, style, false);
    }

    public static String format(double n, boolean round) {
        return format(n, Style.ABBREVIATED, round);
    }

    public static String format(double n, Style style, boolean round) {
        return switch (style) {
            case ABBREVIATED -> abbreviate(n, round);
            case SPACED -> spaced(n);
        };
    }

    private static String abbreviate(double n, boolean round) {
        double magnitude = Math.log(Math.max(Math.abs(n), 1)) / Math.log(1000);
        int step = (int) Math.floor(magnitude);
        double scaled = n / Math.pow(1000, step);
        String suffix = (step >= 1 && step <= SUFFIXES.length) ? SUFFIXES[step - 1] : "";
        String value = round ? String.valueOf(Math.round(scaled)) : trimTrailingZeros(scaled);
        return value + suffix;
    }

    /** Comma-groups a number, e.g. 1234567.891 -> "1,234,567.89" (decimals are truncated to the last 2 digits). */
    public static String spaced(double n) {
        String value = trimTrailingZeros(n);
        int dot = value.indexOf('.');
        if (dot < 0) {
            return groupThousands(value);
        }

        String whole = value.substring(0, dot);
        String decimals = value.substring(dot + 1);
        String tail = decimals.length() > 2 ? decimals.substring(decimals.length() - 2) : decimals;
        return groupThousands(whole) + "." + tail;
    }

    private static String groupThousands(String digits) {
        return String.join(",", digits.split("(?<=\\d)(?=(\\d\\d\\d)+(?!\\d))"));
    }

    /**
     * Small-caps a string, leaving legacy "&amp;" format codes (e.g.
     * {@code &7}) intact. Returns a plain string - still legal to embed in
     * a larger MiniMessage/legacy template - rather than parsing it to a
     * Component itself, so it composes with {@link Text#parse} the same
     * way any other template text does.
     */
    public static String fancyFont(String text) {
        String result = text;
        for (int i = 0; i < NORMAL_LETTERS.length(); i++) {
            String normal = String.valueOf(NORMAL_LETTERS.charAt(i));
            String tiny = String.valueOf(TINY_LETTERS.charAt(i));
            result = result.replace(normal, tiny);
            result = result.replace("&" + tiny, "&" + normal);
        }
        return result;
    }

    /** Reverses {@link #abbreviate}, e.g. "1.5K" -> 1500. */
    public static double unformat(String number) {
        String[] parts = number.split("(?<=[0-9])(?=[A-Za-z])", 2);
        double base = Double.parseDouble(parts[0]);
        if (parts.length < 2) {
            return base;
        }

        String suffix = parts[1].toUpperCase(Locale.ROOT);
        int index = 1;
        double output = 0;
        boolean outputSet = false;
        while (!matchesSuffix(suffix, index)) {
            index++;
            output = (outputSet ? output : base) * 1000;
            outputSet = true;
            if (index > 61) {
                break;
            }
        }
        return outputSet ? output : base;
    }

    private static boolean matchesSuffix(String upperSuffix, int index) {
        return index - 1 < UNFORMAT_SUFFIXES.length && UNFORMAT_SUFFIXES[index - 1].equals(upperSuffix);
    }

    private static String trimTrailingZeros(double value) {
        if (value == 0) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
