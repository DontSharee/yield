package me.dontshare.yieldtrade.currency;

import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Mints and redeems {@link CurrencyNoteItem}s.
 * <p>
 * Ordering here is deliberate, for the same reason yield-auctionhouse spells
 * its own out: a balance lives in a cached Java object, so it must only ever
 * be changed by a single, ordinary main-thread step that cannot be replayed.
 * Withdrawing therefore debits first (main thread), writes the ledger row
 * second (async), and only hands over the item third - and refunds on the
 * main thread if the ledger write fails, so a failure can never leave a
 * player short. Redeeming runs the same sequence backwards, and if the
 * player has gone by the time the payout would land, the consumed row is
 * restored rather than the note's value evaporating.
 */
public final class CurrencyNoteService {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final CurrencyNoteStore noteStore;
    private final CurrencyNoteItem noteItem;
    private final PlayerDataStore<PackPlayerProfile> playerStore;

    /** Guards against a second withdraw/redeem from the same player while one is still in flight. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public CurrencyNoteService(JavaPlugin plugin, DatabaseManager databaseManager, CurrencyNoteStore noteStore,
                               CurrencyNoteItem noteItem, PlayerDataStore<PackPlayerProfile> playerStore) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.noteStore = noteStore;
        this.noteItem = noteItem;
        this.playerStore = playerStore;
    }

    public CurrencyNoteItem getNoteItem() {
        return noteItem;
    }

    /** Debits {@code amount} and hands the player a note for it. Main thread only. */
    public void withdraw(Player player, TradeCurrency currency, BigInteger amount) {
        if (amount.signum() <= 0) {
            player.sendMessage(Text.parse("<red>Enter an amount above zero.</red>"));
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
            return;
        }

        PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
        if (profile == null) {
            busy.remove(player.getUniqueId());
            player.sendMessage(Text.parse("<red>Your profile isn't loaded yet - try again in a moment.</red>"));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            busy.remove(player.getUniqueId());
            player.sendMessage(Text.parse("<red>You need a free inventory slot to withdraw.</red>"));
            return;
        }
        if (!currency.deduct(profile, amount)) {
            busy.remove(player.getUniqueId());
            player.sendMessage(Text.parse("<red>You don't have that many <name>.</red>",
                    Placeholder.unparsed("name", currency.displayName())));
            return;
        }
        playerStore.save(player.getUniqueId());

        UUID noteId = UUID.randomUUID();
        databaseManager.supplyAsync(() -> {
            noteStore.issue(noteId, player.getUniqueId(), currency, amount);
            return true;
        }).whenComplete((issued, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(player.getUniqueId());
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to issue currency note for " + player.getUniqueId()
                        + "; refunding " + amount + " " + currency, error);
                refund(player.getUniqueId(), currency, amount);
                player.sendMessage(Text.parse("<red>Withdrawal failed - your balance is unchanged.</red>"));
                return;
            }
            giveOrDrop(player, noteItem.create(noteId, currency, amount));
            player.sendMessage(Text.parse("<green>Withdrew <amount> <name>.</green>",
                    Placeholder.unparsed("amount", Formatting.spaced(amount)),
                    Placeholder.unparsed("name", currency.displayName())));
        }));
    }

    /** Consumes the held note and credits its value back. Main thread only. */
    public void redeem(Player player, ItemStack heldNote) {
        CurrencyNoteItem.Note note = noteItem.read(heldNote);
        if (note == null) {
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
            return;
        }

        UUID playerId = player.getUniqueId();
        databaseManager.supplyAsync(() -> noteStore.redeem(note.noteId()))
                .whenComplete((redeemed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(playerId);
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to redeem note " + note.noteId(), error);
                        player.sendMessage(Text.parse("<red>Couldn't reach the bank - try again in a moment.</red>"));
                        return;
                    }
                    if (redeemed == null) {
                        // No live row: this note was already redeemed, so this
                        // copy of it is worth nothing. Left in the player's
                        // inventory rather than destroyed - refusing to pay is
                        // enough, and destroying it would turn any future false
                        // negative into real item loss.
                        plugin.getLogger().warning("Refused redemption of note " + note.noteId() + " by " + playerId
                                + " (face value " + note.amount() + " " + note.currency() + ") - no live ledger row.");
                        player.sendMessage(Text.parse("<red>This note has already been redeemed.</red>"));
                        return;
                    }

                    Player online = Bukkit.getPlayer(playerId);
                    PackPlayerProfile profile = online != null ? playerStore.getCached(playerId) : null;
                    if (profile == null) {
                        // Consumed but unpayable - put it back so its value survives.
                        databaseManager.supplyAsync(() -> {
                            noteStore.restore(note.noteId(), playerId, redeemed.currency(), redeemed.amount());
                            return true;
                        });
                        return;
                    }

                    if (!consumeHeldNote(online, note.noteId())) {
                        databaseManager.supplyAsync(() -> {
                            noteStore.restore(note.noteId(), playerId, redeemed.currency(), redeemed.amount());
                            return true;
                        });
                        online.sendMessage(Text.parse("<red>Couldn't find that note in your hand anymore.</red>"));
                        return;
                    }

                    redeemed.currency().credit(profile, redeemed.amount());
                    playerStore.save(playerId);
                    online.sendMessage(Text.parse("<green>Redeemed <amount> <name>.</green>",
                            Placeholder.unparsed("amount", Formatting.spaced(redeemed.amount())),
                            Placeholder.unparsed("name", redeemed.currency().displayName())));
                }));
    }

    /**
     * Removes the one note carrying this exact id from the player's hand.
     * Re-checked at payout time rather than trusting the stack captured when
     * the click arrived, since a tick has passed since then.
     */
    private boolean consumeHeldNote(Player player, UUID noteId) {
        ItemStack held = player.getInventory().getItemInMainHand();
        CurrencyNoteItem.Note inHand = noteItem.read(held);
        if (inHand == null || !inHand.noteId().equals(noteId)) {
            return false;
        }
        held.setAmount(held.getAmount() - 1);
        return true;
    }

    private void refund(UUID playerId, TradeCurrency currency, BigInteger amount) {
        PackPlayerProfile profile = playerStore.getCached(playerId);
        if (profile == null) {
            plugin.getLogger().severe("Could not refund " + amount + " " + currency + " to " + playerId
                    + " - profile no longer cached. Requires manual correction.");
            return;
        }
        currency.credit(profile, amount);
        playerStore.save(playerId);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
