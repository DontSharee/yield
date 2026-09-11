package me.dontshare.yieldranks;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldleveling.YieldLeveling;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldranks.command.DonorRankCommand;
import me.dontshare.yieldranks.data.DonorRank;
import me.dontshare.yieldranks.data.RankContentLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
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
        rankService = new DonorRankService(() -> ranks, packs.getPlayerStore());

        packs.registerCoinMultiplierProvider(PROVIDER_KEY, rankService::coinMultiplier);
        packs.registerGemMultiplierProvider(PROVIDER_KEY, rankService::gemMultiplier);
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY, rankService::luckBonus);
        packs.getEquipmentService().registerBonusEquipSlotsProvider(PROVIDER_KEY, rankService::bonusPetSlots);
        packs.getEnchantService().registerBonusSlotProvider(PROVIDER_KEY, rankService::bonusEnchantSlots);
        leveling.getLevelingService().registerXpMultiplierProvider(PROVIDER_KEY, rankService::xpMultiplier);

        core.getAdminCommandRegistry().register(DonorRankCommand.buildAdminDomain(this, rankService));
        CommandManager.register(this, DonorRankCommand.buildInfo(rankService, packs.getPlayerStore()),
                "View your current donor rank and its perks", List.of());
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterGemMultiplierProvider(PROVIDER_KEY);
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
