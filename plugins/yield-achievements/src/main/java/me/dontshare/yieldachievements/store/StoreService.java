package me.dontshare.yieldachievements.store;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Supplier;

/** The /buy screen's purchase logic - deducts Credits, then runs the product's own configured console commands. */
public final class StoreService {

    public enum PurchaseResult {
        SUCCESS,
        NOT_ENOUGH_CREDITS,
        UNKNOWN_PRODUCT
    }

    private final Supplier<Map<String, StoreProduct>> content;
    private final PlayerDataStore<PackPlayerProfile> store;

    public StoreService(Supplier<Map<String, StoreProduct>> content, PlayerDataStore<PackPlayerProfile> store) {
        this.content = content;
        this.store = store;
    }

    public PurchaseResult purchase(Player player, String productId) {
        StoreProduct product = content.get().get(productId);
        if (product == null) {
            return PurchaseResult.UNKNOWN_PRODUCT;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.getCredits().compareTo(product.cost()) < 0) {
            return PurchaseResult.NOT_ENOUGH_CREDITS;
        }
        profile.setCredits(profile.getCredits().subtract(product.cost()));
        store.save(player.getUniqueId());
        for (String command : product.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        return PurchaseResult.SUCCESS;
    }
}
