package me.dontshare.yieldranks;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldranks.data.RankProfile;
import me.dontshare.yieldleveling.YieldLeveling;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldranks.command.DonorRankCommand;
import me.dontshare.yieldranks.data.DonorRank;
import me.dontshare.yieldranks.data.RankContentLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class YieldRanks extends JavaPlugin {

    private static final String PROVIDER_KEY = "donor_rank";

    private RankContentLoader contentLoader;
    private volatile Map<String, DonorRank> ranks;
    private DonorRankService rankService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        YieldLeveling leveling = JavaPlugin.getPlugin(YieldLeveling.class);

        contentLoader = new RankContentLoader(this, getLogger());
        ranks = contentLoader.load();
        rankService = new DonorRankService(() -> ranks, PlayerStores.register(
                this, core.getListenerManager(), core.getDatabaseManager(),
                "ranks", RankProfile.class, RankProfile::new, "donor rank data"));

        packs.registerCoinMultiplierProvider(PROVIDER_KEY, profile -> rankService.coinMultiplier(profile.getPlayerId()));
        packs.registerDiamondMultiplierProvider(PROVIDER_KEY, profile -> rankService.diamondMultiplier(profile.getPlayerId()));
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY, profile -> rankService.luckBonus(profile.getPlayerId()));
        packs.getEquipmentService().registerBonusEquipSlotsProvider(PROVIDER_KEY, profile -> rankService.bonusPetSlots(profile.getPlayerId()));
        packs.getEnchantService().registerBonusSlotProvider(PROVIDER_KEY, profile -> rankService.bonusEnchantSlots(profile.getPlayerId()));
        leveling.getLevelingService().registerXpMultiplierProvider(PROVIDER_KEY,
                player -> rankService.xpMultiplier(player.getUniqueId()));

        core.getAdminCommandRegistry().register(DonorRankCommand.buildAdminDomain(this, rankService));
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterDiamondMultiplierProvider(PROVIDER_KEY);
            packs.getLuckService().unregisterExtraLuckProvider(PROVIDER_KEY);
            packs.getEquipmentService().unregisterBonusEquipSlotsProvider(PROVIDER_KEY);
            packs.getEnchantService().unregisterBonusSlotProvider(PROVIDER_KEY);
        }
        YieldLeveling leveling = JavaPlugin.getPlugin(YieldLeveling.class);
        if (leveling != null) {
            leveling.getLevelingService().unregisterXpMultiplierProvider(PROVIDER_KEY);
        }
    }

    /** Re-reads ranks.yml - existing donorRankId values on player profiles that no longer resolve just stop applying any bonus (never crash), same tolerant behavior as every other content-reload path in this project. */
    public void reloadContent() {
        ranks = contentLoader.load();
    }

    public DonorRankService getRankService() {
        return rankService;
    }
}
