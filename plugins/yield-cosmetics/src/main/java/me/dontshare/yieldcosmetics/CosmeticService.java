package me.dontshare.yieldcosmetics;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcosmetics.data.Cosmetic;
import me.dontshare.yieldcosmetics.data.CosmeticCategory;
import me.dontshare.yieldcosmetics.data.CosmeticContentLoader.Content;
import me.dontshare.yieldcosmetics.data.CosmeticProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * Ownership + equip logic for all three cosmetic categories. Ownership is
 * purely permission-based (see {@link Cosmetic#isOwnedBy}) - re-checked at
 * every use, not just at equip time, so a permission revoked later (e.g. a
 * refunded voucher) takes effect immediately without needing a re-equip,
 * the same "check every time, not just at toggle time" approach yield-packs'
 * AutoFuseService already established for permission-gated behavior.
 */
public final class CosmeticService {

    public enum EquipResult { NOT_FOUND, LOCKED, EQUIPPED }

    private final Supplier<Content> content;
    private final PlayerDataStore<CosmeticProfile> store;
    private final NameplateService nameplateService;

    public CosmeticService(Supplier<Content> content, PlayerDataStore<CosmeticProfile> store, NameplateService nameplateService) {
        this.content = content;
        this.store = store;
        this.nameplateService = nameplateService;
    }

    public Content content() {
        return content.get();
    }

    public EquipResult equip(Player player, CosmeticCategory category, String cosmeticId) {
        Cosmetic cosmetic = content.get().byCategory(category).get(cosmeticId);
        if (cosmetic == null) {
            return EquipResult.NOT_FOUND;
        }
        if (!cosmetic.isOwnedBy(player)) {
            return EquipResult.LOCKED;
        }
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        category.setEquippedId(profile, cosmeticId);
        store.save(player.getUniqueId());
        if (category == CosmeticCategory.NAMEPLATE) {
            applyEquippedNameplate(player);
        }
        return EquipResult.EQUIPPED;
    }

    public void unequip(Player player, CosmeticCategory category) {
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        category.setEquippedId(profile, null);
        store.save(player.getUniqueId());
        if (category == CosmeticCategory.NAMEPLATE) {
            applyEquippedNameplate(player);
        }
    }

    /** Re-derives and re-applies this player's nameplate team from their current profile - call on join too, not just on equip/unequip. */
    public void applyEquippedNameplate(Player player) {
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        nameplateService.apply(player, activeStyle(player, profile, CosmeticCategory.NAMEPLATE));
    }

    /** {@link me.dontshare.yieldcore.chat.ChatFormatter}'s message-style hook. */
    public String messageStyleFor(Player player) {
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        return activeStyle(player, profile, CosmeticCategory.CHAT_COLOR);
    }

    /** {@link me.dontshare.yieldcore.chat.ChatFormatter}'s name-style hook - the equipped nameplate's style, applied to the player's own name in chat (matching what it already does above their head and in the tab list). */
    public String nameStyleFor(Player player) {
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        return activeStyle(player, profile, CosmeticCategory.NAMEPLATE);
    }

    /** {@link me.dontshare.yieldcore.chat.ChatFormatter}'s name-tag hook - the equipped tag's badge, or empty. */
    public Component nameTagFor(Player player) {
        CosmeticProfile profile = store.getOrCreate(player.getUniqueId());
        String badge = activeStyle(player, profile, CosmeticCategory.TAG);
        return badge == null ? Component.empty() : Text.parse(badge);
    }

    private String activeStyle(Player player, CosmeticProfile profile, CosmeticCategory category) {
        String id = category.equippedId(profile);
        if (id == null) {
            return null;
        }
        Cosmetic cosmetic = content.get().byCategory(category).get(id);
        return (cosmetic != null && cosmetic.isOwnedBy(player)) ? cosmetic.style() : null;
    }
}
