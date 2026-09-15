package me.dontshare.yieldtrade.currency;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * The physical, tradeable note {@code /withdraw} mints. Carries its own id,
 * currency and face value in its PersistentDataContainer, but the tags are
 * only ever a <i>claim</i> - {@link CurrencyNoteStore} decides whether that
 * claim is still worth anything, so a copied note reads fine here and is
 * still refused at redemption.
 * <p>
 * Always a stack of exactly one: stacking would let two notes of the same
 * face value merge into a single item that only carries one of the two ids,
 * silently destroying the other's value.
 */
public final class CurrencyNoteItem {

    private final NamespacedKey noteIdKey;
    private final NamespacedKey currencyKey;
    private final NamespacedKey amountKey;

    public CurrencyNoteItem(JavaPlugin plugin) {
        this.noteIdKey = new NamespacedKey(plugin, "note_id");
        this.currencyKey = new NamespacedKey(plugin, "note_currency");
        this.amountKey = new NamespacedKey(plugin, "note_amount");
    }

    public ItemStack create(UUID noteId, TradeCurrency currency, BigInteger amount) {
        String pretty = Formatting.spaced(amount);
        return ItemBuilder.of(Material.PAPER)
                .name(currency.colorTag() + "<bold>" + pretty + " " + currency.displayName() + "</bold>")
                .lore(List.of(
                        " ",
                        "<gray>A bearer note worth " + currency.colorTag() + pretty + " " + currency.displayName() + "<gray>.",
                        " ",
                        "<yellow>Shift + Right-Click <gray>to redeem.",
                        " ",
                        "<dark_gray>" + noteId))
                .tag(noteIdKey, PersistentDataType.STRING, noteId.toString())
                .tag(currencyKey, PersistentDataType.STRING, currency.name())
                .tag(amountKey, PersistentDataType.STRING, amount.toString())
                .hideAttributes()
                .amount(1)
                .build();
    }

    /** Null if this isn't a note at all - says nothing about whether it's still worth anything. */
    public Note read(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String rawId = container.get(noteIdKey, PersistentDataType.STRING);
        String rawCurrency = container.get(currencyKey, PersistentDataType.STRING);
        String rawAmount = container.get(amountKey, PersistentDataType.STRING);
        if (rawId == null || rawCurrency == null || rawAmount == null) {
            return null;
        }
        try {
            return new Note(UUID.fromString(rawId), TradeCurrency.valueOf(rawCurrency), new BigInteger(rawAmount));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isNote(ItemStack item) {
        return read(item) != null;
    }

    public record Note(UUID noteId, TradeCurrency currency, BigInteger amount) {
    }
}
