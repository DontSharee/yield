package me.dontshare.yield.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps {@link MiniMessage} so message text can use legacy "&amp;" color and
 * format codes (e.g. {@code &7}, {@code &8}, {@code &l}) as shorthand for
 * the equivalent MiniMessage tags, in the same string as regular MiniMessage
 * tags (hex colors, gradients, placeholders like {@code <target>}). Legacy
 * codes are translated to their tag equivalents before the string is handed
 * to MiniMessage, so both syntaxes can be mixed freely.
 * <p>
 * Color codes ({@code &0}-{@code &f}) also emit a {@code <reset>} first,
 * matching vanilla Minecraft: a color code there always clears any active
 * bold/italic/underline/strikethrough/obfuscated. MiniMessage's tags don't
 * do this on their own - {@code <bold>} and {@code <white>} are independent -
 * so without it, formatting from an earlier code would otherwise bleed into
 * every color change that follows it.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)&([0-9a-fk-or])");
    private static final String COLOR_CODES = "0123456789abcdef";

    private Text() {
    }

    public static Component parse(String message) {
        return MM.deserialize(translateLegacy(message));
    }

    public static Component parse(String message, TagResolver... resolvers) {
        return MM.deserialize(translateLegacy(message), resolvers);
    }

    private static String translateLegacy(String message) {
        Matcher matcher = LEGACY_CODE.matcher(message);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String tag = tagFor(matcher.group(1).toLowerCase(Locale.ROOT));
            matcher.appendReplacement(result, Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String tagFor(String code) {
        String tag = switch (code) {
            case "0" -> "<black>";
            case "1" -> "<dark_blue>";
            case "2" -> "<dark_green>";
            case "3" -> "<dark_aqua>";
            case "4" -> "<dark_red>";
            case "5" -> "<dark_purple>";
            case "6" -> "<gold>";
            case "7" -> "<gray>";
            case "8" -> "<dark_gray>";
            case "9" -> "<blue>";
            case "a" -> "<green>";
            case "b" -> "<aqua>";
            case "c" -> "<red>";
            case "d" -> "<light_purple>";
            case "e" -> "<yellow>";
            case "f" -> "<white>";
            case "k" -> "<obfuscated>";
            case "l" -> "<bold>";
            case "m" -> "<strikethrough>";
            case "n" -> "<underlined>";
            case "o" -> "<italic>";
            case "r" -> "<reset>";
            default -> "&" + code;
        };
        return COLOR_CODES.contains(code) ? "<reset>" + tag : tag;
    }
}
