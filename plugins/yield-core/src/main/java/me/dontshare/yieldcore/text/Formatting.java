package me.dontshare.yieldcore.text;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Number/text formatting helpers ({@code format}, {@code spaced},
 * {@code unformat}, {@code fancyFont}).
 */
public final class Formatting {

    private static final Pattern LEADING_LEGACY_CODES = Pattern.compile("^(&[0-9a-fk-or])+", Pattern.CASE_INSENSITIVE);

    /** {@link #format(double, Style, boolean)} suffixes, index 0 = "K" (i.e. divisor 1000^1). */
    private static final String[] SUFFIXES = {
            "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc", "Ud", "Td",
            "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod", "Vg", "Uvg", "Dvg", "Tvg", "Qavg",
            "Qivg", "Sxvg", "Spvg", "Ocvg", "Novg", "Nov", "Tt", "Unt", "Dtt", "Ttt", "Qut", "Qat", "Sst"
    };

    /** {@link #unformat}'s lookup list - "0" is a placeholder so K aligns with index 1, matching one 1000x step. */
    private static final String[] UNFORMAT_SUFFIXES = {
            "0", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc", "Ud", "Td",
            "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod", "Vg", "Uvg", "Dvg", "Tvg", "Qavg",
            "Qivg", "Sxvg", "Spvg", "Ocvg", "Novg", "Nov", "Tt", "Unt", "Dtt", "Ttt", "Qut", "Qat", "Sst"
    };

    private static final String NORMAL_LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String TINY_LETTERS = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    /**
     * {@link #fancyFont} results, keyed by input. Converting one string walks
     * the alphabet twice over, allocating a fresh copy each pass - fine once,
     * but the sidebar and the pack selector item call it on fixed labels for
     * every player every second, where it was pure repeated work.
     */
    private static final java.util.Map<String, String> FANCY_FONT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int FANCY_FONT_CACHE_LIMIT = 512;

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

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

    // --- BigInteger overloads ---
    //
    // A double only has exact-integer precision up to 2^53 (~9.007e15) -
    // far below what a currency balance meant to reach the "Sst" suffix
    // (~10^115) needs. These overloads never round-trip the FULL value
    // through double: spaced() works on the exact digit string, and
    // abbreviate's scaled mantissa is always < 1000 by construction (via
    // BigDecimal division, not a double cast of the original value) before
    // it's ever turned into a double for display.

    public static String format(BigInteger n) {
        return format(n, Style.ABBREVIATED, false);
    }

    public static String format(BigInteger n, Style style) {
        return format(n, style, false);
    }

    public static String format(BigInteger n, boolean round) {
        return format(n, Style.ABBREVIATED, round);
    }

    public static String format(BigInteger n, Style style, boolean round) {
        return switch (style) {
            case ABBREVIATED -> abbreviateBig(n, round);
            case SPACED -> spaced(n);
        };
    }

    /** Comma-groups an exact BigInteger, e.g. 123456789012345678901234567890 -> "123,456,789,012,345,678,901,234,567,890". */
    public static String spaced(BigInteger n) {
        return groupThousands(n.toString());
    }

    private static String abbreviateBig(BigInteger n, boolean round) {
        BigInteger abs = n.abs();
        if (abs.compareTo(BigInteger.valueOf(1000)) < 0) {
            return n.toString();
        }
        int digits = abs.toString().length();
        int step = (digits - 1) / 3;
        String suffix = (step >= 1 && step <= SUFFIXES.length) ? SUFFIXES[step - 1] : "";

        BigDecimal divisor = BigDecimal.TEN.pow(step * 3);
        BigDecimal scaled = new BigDecimal(n).divide(divisor, 2, RoundingMode.DOWN);
        String value = round ? String.valueOf(Math.round(scaled.doubleValue())) : trimTrailingZeros(scaled.doubleValue());
        return value + suffix;
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
        String cached = FANCY_FONT_CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        String result = text;
        for (int i = 0; i < NORMAL_LETTERS.length(); i++) {
            String normal = String.valueOf(NORMAL_LETTERS.charAt(i));
            String tiny = String.valueOf(TINY_LETTERS.charAt(i));
            result = result.replace(normal, tiny);
            result = result.replace("&" + tiny, "&" + normal);
        }
        // Bounded rather than unbounded: every caller today passes either a
        // literal or a value from config (a category, a tier word, a bonus
        // id), so the real key set is small and fixed - but a cache on a
        // shared text helper shouldn't be able to grow without limit if that
        // ever stops being true.
        if (FANCY_FONT_CACHE.size() < FANCY_FONT_CACHE_LIMIT) {
            FANCY_FONT_CACHE.put(text, result);
        }
        return result;
    }

    /**
     * Strips any color codes (not format codes like bold/italic) this
     * string starts with, e.g. {@code "&aEmerald Lizard"} -> {@code
     * "Emerald Lizard"}. Content authored with its own leading color (item
     * names, rarity labels, etc. in packs.yml) is meant to be re-colored by
     * the caller instead - {@link Text#parse} translates a leading legacy
     * color code to a MiniMessage {@code <reset>} tag, which clears
     * whatever tag the caller wrapped the text in. Wrapping it in something
     * like {@code "<bold>" + rawName + "</bold>"} would then leave
     * {@code </bold>} with nothing left on the tag stack to close, and
     * MiniMessage prints it as literal text instead of matching it - call
     * this first to strip the embedded code before composing a larger
     * templated string around raw content.
     */
    public static String stripLeadingColorCodes(String text) {
        return LEADING_LEGACY_CODES.matcher(text).replaceFirst("");
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

    /**
     * Same shorthand as {@link #unformat} (e.g. "1.5b" -&gt; 1500000000),
     * but exact - computed via {@link BigDecimal} instead of {@code double},
     * so it never loses precision the way a double-based parse would once
     * values get large (see the BigInteger currency migration this mirrors).
     * Throws {@link NumberFormatException} for a non-numeric base or an
     * unrecognized suffix, same contract as {@code new BigInteger(String)}.
     */
    public static BigInteger unformatToBigInteger(String raw) {
        String trimmed = raw.trim();
        String[] parts = trimmed.split("(?<=[0-9])(?=[A-Za-z])", 2);
        BigDecimal base = new BigDecimal(parts[0]);
        if (parts.length < 2 || parts[1].isEmpty()) {
            return base.toBigInteger();
        }
        String suffix = parts[1];
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (SUFFIXES[i].equalsIgnoreCase(suffix)) {
                return base.multiply(BigDecimal.TEN.pow(3 * (i + 1))).toBigInteger();
            }
        }
        throw new NumberFormatException("Unknown suffix '" + suffix + "'");
    }

    /**
     * Standard subtractive-notation Roman numerals, e.g. 5 -&gt; "V", 2024 ->
     * "MMXXIV" - for {@code n <= 0} returns the plain digits (no such thing
     * as a zero/negative Roman numeral). Never caps out: for an
     * "infinite rank"-style value in the thousands, "M" just keeps
     * repeating (e.g. 4000 -> "MMMM") - unconventional past the classical
     * 3-repeat cap, but unambiguous and keeps working at any size, which a
     * fixed vocabulary (only up to 3999) can't.
     */
    public static String toRoman(int n) {
        if (n <= 0) {
            return String.valueOf(n);
        }
        StringBuilder result = new StringBuilder();
        int remaining = n;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (remaining >= ROMAN_VALUES[i]) {
                remaining -= ROMAN_VALUES[i];
                result.append(ROMAN_SYMBOLS[i]);
            }
        }
        return result.toString();
    }

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * Rounds to 3 decimal places (dropping decimals entirely at 1 million
     * and above) before rendering - a value like {@code damage * levelMultiplier}
     * routinely lands on a double that's one ULP off a "clean" number (e.g.
     * {@code 12.600000000000001}), and {@code Double.toString}'s shortest
     * round-tripping representation of THAT exact bit pattern is the long,
     * ugly string a raw {@code BigDecimal.valueOf(value).stripTrailingZeros()}
     * would show verbatim - actually rounding first (not just trimming
     * zeros afterward) is what fixes that, for every caller of this method.
     */
    private static String trimTrailingZeros(double value) {
        if (value == 0) {
            return "0";
        }
        BigDecimal exact = BigDecimal.valueOf(value);
        if (exact.abs().compareTo(ONE_MILLION) >= 0) {
            return exact.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        return exact.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
