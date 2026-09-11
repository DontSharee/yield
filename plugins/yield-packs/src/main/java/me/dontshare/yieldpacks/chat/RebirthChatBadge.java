package me.dontshare.yieldpacks.chat;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * The "[&lt;rebirths&gt;★ • &lt;prestiges as roman numerals&gt;] " badge
 * shown before a player's name in chat - registered once from
 * {@code YieldPacks#onEnable} via {@code core.getChatFormatter().registerNameTagProvider(...)}.
 * Yield has no standalone "player level" stat, so prestiges (the closest
 * existing second-tier progression counter) fills that role here.
 */
public final class RebirthChatBadge implements Function<Player, Component> {

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
        if (profile.getPrestiges() <= 0) {
            return Text.parse("<gray>[</gray><gold><rebirths>★</gold><gray>] </gray>",
                    Placeholder.unparsed("rebirths", Formatting.format(profile.getRebirths())));
        }
        return Text.parse("<gray>[</gray><gold><rebirths>★</gold> <gray>•</gray> <white><prestige></white><gray>] </gray>",
                Placeholder.unparsed("rebirths", Formatting.format(profile.getRebirths())),
                Placeholder.unparsed("prestige", toRoman(profile.getPrestiges())));
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
