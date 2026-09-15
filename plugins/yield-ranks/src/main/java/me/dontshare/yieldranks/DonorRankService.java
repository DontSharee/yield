package me.dontshare.yieldranks;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcosmetics.CosmeticService;
import me.dontshare.yieldcosmetics.YieldCosmetics;
import me.dontshare.yieldcosmetics.data.CosmeticCategory;
import me.dontshare.yieldranks.data.DonorRank;
import me.dontshare.yieldranks.data.RankProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Donor rank lookups + the purchase-apply flow. Ranks are strictly ordered
 * ({@link DonorRank#sortOrder}) - applying a rank the player already meets
 * or exceeds is a no-op with a message, never a downgrade.
 */
public final class DonorRankService {

    private static final Duration FADE_IN = Duration.ofMillis(200);
    private static final Duration STAY = Duration.ofSeconds(3);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final Supplier<Map<String, DonorRank>> ranks;
    private final PlayerDataStore<RankProfile> store;

    public DonorRankService(Supplier<Map<String, DonorRank>> ranks, PlayerDataStore<RankProfile> store) {
        this.ranks = ranks;
        this.store = store;
    }

    public Optional<DonorRank> currentRank(UUID playerId) {
        String id = store.getOrCreate(playerId).getDonorRankId();
        return id == null ? Optional.empty() : Optional.ofNullable(ranks.get().get(id));
    }

    public double coinMultiplier(UUID playerId) {
        return currentRank(playerId).map(DonorRank::coinMultiplier).orElse(1.0);
    }

    public double diamondMultiplier(UUID playerId) {
        return currentRank(playerId).map(DonorRank::diamondMultiplier).orElse(1.0);
    }

    public double xpMultiplier(UUID playerId) {
        return currentRank(playerId).map(DonorRank::xpMultiplier).orElse(1.0);
    }

    public double luckBonus(UUID playerId) {
        return currentRank(playerId).map(DonorRank::luckBonus).orElse(0.0);
    }

    public int bonusPetSlots(UUID playerId) {
        return currentRank(playerId).map(DonorRank::bonusPetSlots).orElse(0);
    }

    public int bonusEnchantSlots(UUID playerId) {
        return currentRank(playerId).map(DonorRank::bonusEnchantSlots).orElse(0);
    }

    /** Called by "/admin ranks apply &lt;player&gt; &lt;id&gt;" - the last console command a completed store purchase runs. */
    public void applyRank(Player player, String rankId) {
        DonorRank rank = ranks.get().get(rankId);
        if (rank == null) {
            player.sendMessage(Text.parse("<red>Unknown rank '" + rankId + "'.</red>"));
            return;
        }
        RankProfile profile = store.getOrCreate(player.getUniqueId());
        Optional<DonorRank> existing = currentRank(player.getUniqueId());
        if (existing.isPresent() && existing.get().sortOrder() >= rank.sortOrder()) {
            player.sendMessage(Text.parse("<yellow>You already own an equal or higher rank.</yellow>"));
            return;
        }

        profile.setDonorRankId(rankId);
        store.save(player.getUniqueId());

        YieldCosmetics cosmetics = JavaPlugin.getPlugin(YieldCosmetics.class);
        if (cosmetics != null) {
            CosmeticService cosmeticService = cosmetics.getCosmeticService();
            if (rank.chatColorCosmeticId() != null) {
                cosmeticService.equip(player, CosmeticCategory.CHAT_COLOR, rank.chatColorCosmeticId());
            }
            if (rank.tagCosmeticId() != null) {
                cosmeticService.equip(player, CosmeticCategory.TAG, rank.tagCosmeticId());
            }
        }

        Component main = Text.parse("<gold><bold>" + rank.displayName().toUpperCase() + " RANK</bold></gold>");
        Component sub = Text.parse("<gray>Activated! Type /ranks to see your perks.</gray>");
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }
}
