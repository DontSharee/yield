package me.dontshare.yieldcore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Formats chat as "&lt;prefix&gt;&lt;badges&gt;&lt;name&gt; &#9656; &lt;message&gt;",
 * and expands any literal "[item]" in the message into the player's
 * currently held item - shown as "&lt;amount&gt;x &lt;name&gt;", hoverable for
 * the item's real tooltip (name/lore/enchants) via Adventure's native item
 * hover event.
 * <p>
 * The player's typed message is never parsed for formatting - only the
 * prefix (server-controlled, from LuckPerms via PlaceholderAPI) goes
 * through {@link Text}. Parsing player-typed text would let players inject
 * their own colors/formatting (or worse) through chat.
 * <p>
 * Only one {@link AsyncChatEvent.Renderer} can be registered per event, so
 * anything else that wants to affect chat rendering - e.g. yield-cosmetics'
 * equipped chat-color/tag, or yield-packs' rebirth/prestige badge - extends
 * this single renderer via the three hooks below rather than registering a
 * second, competing one. Each hook is a small ordered list of providers
 * (registration order), all composed together, rather than a single slot -
 * more than one plugin can legitimately want to contribute a name badge or
 * style at once (e.g. a rebirth badge AND an equipped cosmetic tag).
 */
public final class ChatFormatter implements Listener {

    private static final String ITEM_TAG = "[item]";

    /** MiniMessage tag(s) to wrap the message text in (e.g. {@code "<#3B9DFF>"}) - null entries from a provider are ignored. */
    private final List<Function<Player, String>> messageStyleProviders = new CopyOnWriteArrayList<>();
    /** A badge Component shown immediately before the player's name - all registered badges are appended in registration order. */
    private final List<Function<Player, Component>> nameTagProviders = new CopyOnWriteArrayList<>();
    /** MiniMessage tag(s) to wrap the player's own name in (e.g. their equipped nameplate style) - null entries from a provider are ignored. */
    private final List<Function<Player, String>> nameStyleProviders = new CopyOnWriteArrayList<>();

    /** Called by whichever plugin owns a chat-color cosmetic (or any other message-styling concern) - see class Javadoc. */
    public void registerMessageStyleProvider(Function<Player, String> provider) {
        messageStyleProviders.add(provider);
    }

    /** Called by whichever plugin owns a nametag/tag cosmetic (or a progress badge like rebirths) - see class Javadoc. */
    public void registerNameTagProvider(Function<Player, Component> provider) {
        nameTagProviders.add(provider);
    }

    /** Called by whichever plugin owns a nameplate cosmetic - see class Javadoc. */
    public void registerNameStyleProvider(Function<Player, String> provider) {
        nameStyleProviders.add(provider);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        String resolvedPrefix = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%")
                : "";
        Component prefix = Text.parse(resolvedPrefix);
        Component badge = composeBadges(player);

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        String style = composeStyle(messageStyleProviders, player);
        Component formattedMessage = expandItemTags(player, rawMessage, style);
        Component nameComponent = styledName(player, composeStyle(nameStyleProviders, player));

        event.renderer((source, sourceDisplayName, message, viewer) -> prefix
                .append(badge)
                .append(nameComponent)
                .append(Text.parse(MenuLore.ACCENT + " ▸ "))
                .append(formattedMessage));
    }

    private Component composeBadges(Player player) {
        Component result = Component.empty();
        for (Function<Player, Component> provider : nameTagProviders) {
            Component piece = provider.apply(player);
            if (piece != null) {
                result = result.append(piece);
            }
        }
        return result;
    }

    /** Concatenates every non-null style tag from every registered provider, in registration order - null if none supplied one. */
    private static String composeStyle(List<Function<Player, String>> providers, Player player) {
        StringBuilder combined = null;
        for (Function<Player, String> provider : providers) {
            String tag = provider.apply(player);
            if (tag == null) {
                continue;
            }
            if (combined == null) {
                combined = new StringBuilder();
            }
            combined.append(tag);
        }
        return combined == null ? null : combined.toString();
    }

    /** {@code nameStyle} is trusted (server-controlled, from the equipped nameplate cosmetic) - the player's own name is still inserted as a literal via {@link Placeholder#unparsed}, same reasoning as {@link #styledText}. */
    private static Component styledName(Player player, String nameStyle) {
        if (nameStyle == null) {
            return Component.text(player.getName(), NamedTextColor.WHITE);
        }
        return Text.parse(nameStyle + "<name>", Placeholder.unparsed("name", player.getName()));
    }

    private static Component expandItemTags(Player player, String rawMessage, String style) {
        if (!rawMessage.contains(ITEM_TAG)) {
            return styledText(rawMessage, style);
        }

        Component itemComponent = buildItemComponent(player);
        Component result = Component.empty();
        String[] parts = rawMessage.split(java.util.regex.Pattern.quote(ITEM_TAG), -1);
        for (int i = 0; i < parts.length; i++) {
            result = result.append(styledText(parts[i], style));
            if (i < parts.length - 1) {
                result = result.append(itemComponent);
            }
        }
        return result;
    }

    /** Player-typed text is never parsed as MiniMessage source (see class Javadoc) - {@code style} is trusted (server-controlled), the text itself is always inserted as a literal via {@link Placeholder#unparsed}. */
    private static Component styledText(String text, String style) {
        if (style == null) {
            return Component.text(text, NamedTextColor.WHITE);
        }
        return Text.parse(style + "<msg>", Placeholder.unparsed("msg", text));
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
