package me.dontshare.yieldcore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Formats chat as "&lt;prefix&gt;&lt;name&gt; &#9654; &lt;message&gt;", and
 * expands any literal "[item]" in the message into the player's currently
 * held item - shown as "&lt;amount&gt;x &lt;name&gt;", hoverable for the
 * item's real tooltip (name/lore/enchants) via Adventure's native item
 * hover event.
 * <p>
 * The player's typed message is never parsed for formatting - only the
 * prefix (server-controlled, from LuckPerms via PlaceholderAPI) goes
 * through {@link Text}. Parsing player-typed text would let players inject
 * their own colors/formatting (or worse) through chat.
 */
public final class ChatFormatter implements Listener {

    private static final String ITEM_TAG = "[item]";

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        String resolvedPrefix = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%")
                : "";
        Component prefix = Text.parse(resolvedPrefix);

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component formattedMessage = expandItemTags(player, rawMessage);

        event.renderer((source, sourceDisplayName, message, viewer) -> prefix
                .append(Component.text(source.getName(), NamedTextColor.WHITE))
                .append(Component.text(" ▶ ", NamedTextColor.GRAY))
                .append(formattedMessage));
    }

    private static Component expandItemTags(Player player, String rawMessage) {
        if (!rawMessage.contains(ITEM_TAG)) {
            return Component.text(rawMessage, NamedTextColor.WHITE);
        }

        Component itemComponent = buildItemComponent(player);
        Component result = Component.empty();
        String[] parts = rawMessage.split(java.util.regex.Pattern.quote(ITEM_TAG), -1);
        for (int i = 0; i < parts.length; i++) {
            result = result.append(Component.text(parts[i], NamedTextColor.WHITE));
            if (i < parts.length - 1) {
                result = result.append(itemComponent);
            }
        }
        return result;
    }

    private static Component buildItemComponent(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            return Component.text(ITEM_TAG, NamedTextColor.WHITE);
        }

        Component itemName = (held.getItemMeta() != null && held.getItemMeta().hasDisplayName())
                ? held.getItemMeta().displayName()
                : Component.text(prettify(held.getType().name()));

        return Component.text(held.getAmount() + "x ", NamedTextColor.GRAY)
                .append(itemName)
                .hoverEvent(held.asHoverEvent());
    }

    private static String prettify(String materialName) {
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
