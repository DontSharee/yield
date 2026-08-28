package me.dontshare.yieldcore.gui;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A clickable sign-style GUI button that cycles through a fixed list of
 * {@link SortOption}s on each click, remembering each player's current
 * choice so re-opening the same screen preserves it (e.g. "Value: High to
 * Low" -&gt; "Rarity: High to Low" -&gt; "Newest to Oldest" -&gt; back to the
 * first). Generic over the item type {@code T} so any plugin's item-grid
 * {@code Gui} screen can reuse this instead of hand-rolling its own
 * sort-cycling state.
 * <p>
 * This class only owns the cycling state, the sorting itself, and the
 * button's icon - wiring the click to actually re-render the screen is the
 * caller's job (call {@link #cycle} then re-open/rebuild the Gui), since
 * only the caller knows how to redraw its own grid.
 */
public final class SortButton<T> {

    private final List<SortOption<T>> options;
    private final Map<UUID, Integer> selected = new ConcurrentHashMap<>();

    public SortButton(List<SortOption<T>> options) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("SortButton needs at least one SortOption");
        }
        this.options = List.copyOf(options);
    }

    /** The player's current sort option (defaults to the first one in the list). */
    public SortOption<T> current(Player player) {
        return options.get(selected.getOrDefault(player.getUniqueId(), 0));
    }

    /** Advances to the next sort option, wrapping around to the first after the last. */
    public void cycle(Player player) {
        int next = (selected.getOrDefault(player.getUniqueId(), 0) + 1) % options.size();
        selected.put(player.getUniqueId(), next);
    }

    /** Returns a new, sorted list - {@code items} itself is left untouched. */
    public List<T> sorted(Player player, List<T> items) {
        List<T> copy = new ArrayList<>(items);
        copy.sort(current(player).comparator());
        return copy;
    }

    private static final String ACCENT = "<#4BD9FF>";

    /** A clickable sign icon labeled with the player's current sort option, following the server's standard button lore. */
    public ItemStack buildIcon(Player player) {
        String currentName = current(player).displayName();
        List<String> lore = MenuLore.button(
                "sorting",
                List.of(" &fChange&7 how items", " &7are &fordered&7!"),
                ACCENT,
                "Change Sort",
                "Click",
                "Click to Change Sort");
        var builder = ItemBuilder.of(Material.OAK_SIGN).name(MenuLore.buttonName(ACCENT, "SORT"));
        lore.forEach(builder::lore);
        return builder
                .lore("")
                .lore("&7Current: &f" + currentName)
                .hideAttributes()
                .build();
    }
}
