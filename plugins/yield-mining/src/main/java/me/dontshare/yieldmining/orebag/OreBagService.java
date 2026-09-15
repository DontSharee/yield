package me.dontshare.yieldmining.orebag;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldmining.forge.SpecialOreItem;
import me.dontshare.yieldmining.forge.SpecialOreTier;
import me.dontshare.yieldmining.data.MiningProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Special Ore's real home - a mined Special Ore never touches the normal
 * inventory (see {@code MiningService#giveDrop}), it goes straight into
 * {@link MiningProfile#getOreBagEntries()} instead, and only becomes a real
 * item again on explicit withdrawal (see {@link OreBagGui}). Entries are
 * encoded as plain strings ("MATERIAL:multiplier:tierId") rather than a
 * record, so the bag is one flat map in the player's document and reads
 * back without a codec of its own.
 * <p>
 * The {@link PackPlayerProfile} the methods here take is only ever used for
 * its player id - callers already hold one, so passing it saves them
 * looking the same player up twice.
 */
public final class OreBagService {

    public record BagEntryView(String entryId, Material material, double multiplier, String tierId) {
    }

    /** Bag contents and ore discoveries both live here now - the pack profile no longer carries either. */
    private final PlayerDataStore<MiningProfile> miningStore;
    private final SpecialOreItem specialOreItem;
    private final Supplier<List<SpecialOreTier>> tiers;

    public OreBagService(PlayerDataStore<MiningProfile> miningStore, SpecialOreItem specialOreItem,
                          Supplier<List<SpecialOreTier>> tiers) {
        this.miningStore = miningStore;
        this.specialOreItem = specialOreItem;
        this.tiers = tiers;
    }

    /** Called from MiningService.giveDrop instead of handing the item straight to the player. */
    public void add(PackPlayerProfile profile, Player player, Material material, double multiplier, SpecialOreTier tier) {
        String entryId = UUID.randomUUID().toString();
        MiningProfile mining = miningStore.getOrCreate(profile.getPlayerId());
        mining.getOreBagEntries().put(entryId, material.name() + ":" + multiplier + ":" + tier.id());
        mining.getDiscoveredOreMaterials().add(material.name());
        miningStore.save(player.getUniqueId());
        if (mining.isOreBagNotificationsEnabled()) {
            player.sendMessage(Text.parse("<green>Special Ore!</green> <gray>" + prettyName(material) + " ("
                    + String.format(Locale.ROOT, "%.2f", multiplier) + "x) added to your Ore Bag.</gray>"));
        }
    }

    /** Decoded entries, oldest-obtained first - preserves the backing LinkedHashMap's insertion order. */
    public List<BagEntryView> entries(PackPlayerProfile profile) {
        List<BagEntryView> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : miningStore.getOrCreate(profile.getPlayerId()).getOreBagEntries().entrySet()) {
            BagEntryView view = decode(entry.getKey(), entry.getValue());
            if (view != null) {
                result.add(view);
            }
        }
        return result;
    }

    /** Removes one entry and hands the player the real item (dropped at their feet if their inventory is full). False if the entry id no longer exists (e.g. a double-click). */
    public boolean withdraw(PackPlayerProfile profile, Player player, String entryId) {
        String encoded = miningStore.getOrCreate(profile.getPlayerId()).getOreBagEntries().remove(entryId);
        if (encoded == null) {
            return false;
        }
        BagEntryView view = decode(entryId, encoded);
        miningStore.save(player.getUniqueId());
        if (view == null) {
            return true;
        }
        giveItem(player, displayItemFor(view));
        return true;
    }

    /** The exact item a bag entry looks like - same builder a fresh drop uses, so OreBagGui's slots and a withdrawn item are pixel-identical. */
    public ItemStack displayItemFor(BagEntryView entry) {
        return specialOreItem.create(entry.material(), entry.multiplier(), resolveTier(entry.tierId(), entry.multiplier()));
    }

    private SpecialOreTier resolveTier(String tierId, double multiplier) {
        for (SpecialOreTier tier : tiers.get()) {
            if (tier.id().equals(tierId)) {
                return tier;
            }
        }
        // Config changed since this was rolled (tier renamed/removed) - a synthetic stand-in so
        // reconstruction never throws and the player never silently loses the item.
        return new SpecialOreTier(tierId, tierId, 1, multiplier, multiplier);
    }

    private BagEntryView decode(String entryId, String encoded) {
        String[] parts = encoded.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        Material material = Material.matchMaterial(parts[0]);
        if (material == null) {
            return null;
        }
        double multiplier;
        try {
            multiplier = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new BagEntryView(entryId, material, multiplier, parts[2]);
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private String prettyName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
