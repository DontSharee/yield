package me.dontshare.yieldtools;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldtools.data.ToolDefinition;
import me.dontshare.yieldtools.data.ToolProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;

/**
 * The tool path: what a player owns, what they can buy next, and what their
 * held tool does to a tap.
 * <p>
 * Ownership is one number, how far down the path they are (see
 * {@link ToolProfile}), because the path is linear - there is never a
 * question of which tools someone has, only how many.
 */
public final class ToolService {

    /** The hotbar slot a tool goes in when it has nowhere else - the first slot, where a weapon goes. */
    public static final int TOOL_SLOT = 0;

    public enum BuyResult { SUCCESS, MAXED, TOO_POOR }

    private final Supplier<List<ToolDefinition>> tools;
    private final PlayerDataStore<ToolProfile> store;
    private final YieldPacks packs;
    private final ToolItem toolItem;

    public ToolService(Supplier<List<ToolDefinition>> tools, PlayerDataStore<ToolProfile> store, YieldPacks packs,
                       ToolItem toolItem) {
        this.tools = tools;
        this.store = store;
        this.packs = packs;
        this.toolItem = toolItem;
    }

    public List<ToolDefinition> all() {
        return tools.get();
    }

    /**
     * How far down the path this player is, clamped to the path as it is
     * now - a tools.yml that lost entries must not leave anyone pointing
     * past its end. Never below 0: every player owns the first weapon from
     * the moment they join, so there is no "no weapon yet" state.
     */
    public int ownedIndex(Player player) {
        int owned = store.getOrCreate(player.getUniqueId()).getOwnedIndex();
        return Math.max(0, Math.min(owned, tools.get().size() - 1));
    }

    /** The best tool this player owns - null only if tools.yml is empty. */
    public ToolDefinition current(Player player) {
        int index = ownedIndex(player);
        return index < 0 ? null : tools.get().get(index);
    }

    /** The one tool this player can buy now, or null at the end of the path. */
    public ToolDefinition next(Player player) {
        int index = ownedIndex(player) + 1;
        return index < tools.get().size() ? tools.get().get(index) : null;
    }

    /**
     * The share of pet power this player's taps deal right now: their best
     * weapon's power if they are holding it, and null (bare hands, see
     * TapService#BARE_TAP_POWER) otherwise. A weapon is hand-held on
     * purpose - putting it away puts the bonus away. It is the player's OWN
     * best weapon that counts, whatever weapon item is in their hand, so a
     * stray copy is worth nothing (see ToolItem).
     */
    public Double tapPower(Player player) {
        ToolDefinition tool = current(player);
        if (tool == null || !toolItem.isTool(player.getInventory().getItemInMainHand())) {
            return null;
        }
        return tool.power();
    }

    /** Buys the next tool on the path if the player can afford it. */
    public BuyResult buyNext(Player player) {
        ToolDefinition next = next(player);
        if (next == null) {
            return BuyResult.MAXED;
        }
        PackPlayerProfile wallet = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        BigInteger cost = BigInteger.valueOf(next.costCoins());
        if (wallet.getCoins().compareTo(cost) < 0) {
            return BuyResult.TOO_POOR;
        }
        wallet.setCoins(wallet.getCoins().subtract(cost));
        store.getOrCreate(player.getUniqueId()).setOwnedIndex(next.index());
        packs.getPlayerStore().save(player.getUniqueId());
        store.save(player.getUniqueId());
        refreshItem(player);
        return BuyResult.SUCCESS;
    }

    /**
     * Buys as far down the path as the player's coins reach, in order, and
     * returns how many that was. One save and one item swap at the end,
     * however many it bought.
     */
    public int buyMax(Player player) {
        PackPlayerProfile wallet = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        ToolProfile profile = store.getOrCreate(player.getUniqueId());
        List<ToolDefinition> path = tools.get();
        int bought = 0;
        int index = ownedIndex(player);
        while (index + 1 < path.size()) {
            BigInteger cost = BigInteger.valueOf(path.get(index + 1).costCoins());
            if (wallet.getCoins().compareTo(cost) < 0) {
                break;
            }
            wallet.setCoins(wallet.getCoins().subtract(cost));
            index++;
            bought++;
        }
        if (bought > 0) {
            profile.setOwnedIndex(index);
            packs.getPlayerStore().save(player.getUniqueId());
            store.save(player.getUniqueId());
            refreshItem(player);
        }
        return bought;
    }

    /** Admin: put a player at a point on the path directly - 0 is the starting weapon. */
    public void setOwned(Player player, int index) {
        store.getOrCreate(player.getUniqueId()).setOwnedIndex(Math.max(0, Math.min(index, tools.get().size() - 1)));
        store.save(player.getUniqueId());
        refreshItem(player);
    }

    /**
     * Makes sure the player is carrying exactly one tool item, their best,
     * in {@link #TOOL_SLOT} - the slot it is pinned to (see ToolListener).
     * Whatever else is sitting in that slot is moved to a free slot first,
     * or dropped at the player's feet if there is none, so the weapon never
     * silently fails to appear.
     */
    public void refreshItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (toolItem.isTool(inventory.getItem(i))) {
                inventory.setItem(i, null);
            }
        }
        ToolDefinition tool = current(player);
        if (tool == null) {
            return;
        }
        ItemStack occupant = inventory.getItem(TOOL_SLOT);
        inventory.setItem(TOOL_SLOT, toolItem.create(tool));
        if (occupant != null && !occupant.getType().isAir()) {
            inventory.addItem(occupant).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }
}
