package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.gui.EggCatalogGui;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The logic behind the physical Egg Book item - kept separate from
 * PackSelectorListener so the Bukkit event plumbing doesn't get tangled up
 * with the actual behavior.
 * <p>
 * It used to be a Pack Selector: it picked which pack was "active" and
 * right-clicking it opened one on the spot, anywhere in the world. Eggs are
 * hatched at their own stations now, so both halves of that job are gone -
 * what is left, and what it still earns its hotbar slot for, is being the
 * fastest way to look up which egg drops what (see {@link EggCatalogGui}).
 */
public final class PackSelectorService {

    private final PackSelectorItem item;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final EggCatalogGui eggCatalogGui;
    /** Pack name last written into slot 4 per player - see {@link #refreshItem}. */
    private final java.util.Map<java.util.UUID, String> lastRenderedPackName = new java.util.concurrent.ConcurrentHashMap<>();

    public PackSelectorService(PackSelectorItem item, PlayerDataStore<PackPlayerProfile> store,
                                Supplier<PackContentLoader.ContentSnapshot> content, EggCatalogGui eggCatalogGui) {
        this.item = item;
        this.store = store;
        this.content = content;
        this.eggCatalogGui = eggCatalogGui;
    }

    /** Makes sure slot 4 holds the selector - self-healing, so a lost/misplaced item just reappears next join. */
    public void ensureItem(Player player) {
        if (!item.isPackSelector(player.getInventory().getItem(4))) {
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            write(player, selectedPackName(profile));
        }
    }

    /**
     * Rewrites slot 4's item so its lore matches the current selection - a
     * no-op if slot 4 doesn't currently hold the selector, so this never
     * fights a Creative player who's deliberately swapped it out (unlike
     * {@link #ensureItem}, which is only ever called at join).
     */
    public void refreshItem(Player player) {
        if (!item.isPackSelector(player.getInventory().getItem(4))) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String packName = selectedPackName(profile);
        // Called on a timer for every player, but the selection changes maybe
        // once a minute. Rebuilding regardless meant constructing a fresh
        // ItemStack and meta - seven parsed components - and marking the slot
        // dirty (so, an inventory packet) every second, per player, to
        // produce the item that was already there.
        if (packName.equals(lastRenderedPackName.get(player.getUniqueId()))) {
            return;
        }
        write(player, packName);
    }

    private void write(Player player, String packName) {
        lastRenderedPackName.put(player.getUniqueId(), packName);
        player.getInventory().setItem(4, item.create(packName));
    }

    /** Both clicks open the egg catalog - see this class's own note on why there is nothing else left for it to do. */
    public void openSelectMenu(Player player) {
        eggCatalogGui.open(player);
    }

    /** Right-click is the same as left-click. Kept as its own method so PackSelectorListener doesn't have to care. */
    public void attemptOpen(Player player) {
        eggCatalogGui.open(player);
    }

    private String selectedPackName(PackPlayerProfile profile) {
        String activeId = profile.getActivePackId();
        return activeId == null ? "None" : packName(activeId);
    }

    private String packName(String packId) {
        String raw = content.get().packs().find(packId).map(PackDefinition::displayName).orElse(packId);
        return Formatting.stripLeadingColorCodes(raw);
    }
}
