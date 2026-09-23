package me.dontshare.yieldblocktree;

import me.dontshare.yieldblocktree.command.BlockTreeAdminCommand;
import me.dontshare.yieldblocktree.command.BlockTreeCommand;
import me.dontshare.yieldblocktree.data.BlockTreeContentLoader;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.gui.BlockTreeCategoryGui;
import me.dontshare.yieldblocktree.gui.BlockTreeGui;
import me.dontshare.yieldblocktree.listener.BlockTreeProgressListener;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldblocktree.data.BlockTreeProfile;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public final class YieldBlockTree extends JavaPlugin {

    private static final String PROVIDER_KEY = "blocktree";

    private BlockTreeContentLoader contentLoader;
    private volatile Map<Material, BlockTreeDefinition> content;
    private PlayerDataStore<BlockTreeProfile> blockStore;
    private BlockTreeService blockTreeService;
    private BlockTreeFeedback blockTreeFeedback;
    private BlockPerkService perkService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        YieldZones zones = JavaPlugin.getPlugin(YieldZones.class);

        contentLoader = new BlockTreeContentLoader(this, getLogger());
        content = contentLoader.load();
        blockStore = PlayerStores.register(this, core.getListenerManager(), core.getDatabaseManager(),
                "blocktree", BlockTreeProfile.class, BlockTreeProfile::new, "block tree data");
        blockTreeService = new BlockTreeService(() -> content, packs.getPlayerStore(), blockStore);

        registerProviders(packs, zones);
        blockTreeFeedback = new BlockTreeFeedback(blockTreeService, packs);
        Bukkit.getPluginManager().registerEvents(new BlockTreeProgressListener(blockTreeFeedback), this);
        perkService = new BlockPerkService(this, blockTreeService, blockTreeFeedback, packs, zones, () -> content);
        perkService.register();
        Bukkit.getPluginManager().registerEvents(perkService, this);

        BlockTreeCategoryGui categoryGui = new BlockTreeCategoryGui(() -> content, packs.getPlayerStore(), blockTreeService, core.getGuiManager());
        BlockTreeGui hubGui = new BlockTreeGui(() -> content, packs.getPlayerStore(), blockTreeService, core.getGuiManager(), categoryGui);
        categoryGui.setHubGui(hubGui);

        CommandManager.register(this, BlockTreeCommand.build(hubGui), "View your blocktree progress", List.of());
        core.getAdminCommandRegistry().register(BlockTreeAdminCommand.build(this));
    }

    @Override
    public void onDisable() {
        if (perkService != null) {
            perkService.unregister();
        }
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterDamageMultiplierProvider(PROVIDER_KEY);
            packs.unregisterAttackSpeedMultiplierProvider(PROVIDER_KEY);
            packs.getLuckService().unregisterExtraLuckProvider(PROVIDER_KEY);
            packs.getPackOpenService().unregisterCooldownMultiplierProvider(PROVIDER_KEY);
            packs.getPackRollService().unregisterExclusiveFindChanceProvider(PROVIDER_KEY);
        }
        YieldZones zones = JavaPlugin.getPlugin(YieldZones.class);
        if (zones != null) {
            zones.getCubeService().unregisterFlatCoinBonusProvider(PROVIDER_KEY);
            zones.getCubeService().unregisterFlatDiamondBonusProvider(PROVIDER_KEY);
            zones.getCubeService().unregisterBlockCoinMultiplierProvider(PROVIDER_KEY);
            zones.getCubeService().unregisterDiamondChanceBoostProvider(PROVIDER_KEY);
            zones.getPetCombatController().unregisterDoubleHitChanceProvider(PROVIDER_KEY);
            zones.getPetCombatController().unregisterTripleHitChanceProvider(PROVIDER_KEY);
        }
    }

    /** Re-reads blocktree.yml - existing GUI/service instances keep working against the same, now-updated content supplier. */
    public void reloadContent() {
        content = contentLoader.load();
        blockTreeService.invalidateAllPerks();
    }

    public BlockTreeService getBlockTreeService() {
        return blockTreeService;
    }

    private void registerProviders(YieldPacks packs, YieldZones zones) {
        packs.registerCoinMultiplierProvider(PROVIDER_KEY, blockTreeService::globalCoinMultiplierContribution);
        packs.registerDamageMultiplierProvider(PROVIDER_KEY, blockTreeService::globalDamageMultiplierContribution);
        packs.registerAttackSpeedMultiplierProvider(PROVIDER_KEY, blockTreeService::globalAttackSpeedMultiplierContribution);
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY, blockTreeService::globalLuckBoostContribution);
        packs.getPackOpenService().registerCooldownMultiplierProvider(PROVIDER_KEY, blockTreeService::rollSpeedMultiplierContribution);
        packs.getPackRollService().registerExclusiveFindChanceProvider(PROVIDER_KEY, blockTreeService::exclusiveFindChance);

        zones.getCubeService().registerFlatCoinBonusProvider(PROVIDER_KEY, blockTreeService::flatCoinBonus);
        zones.getCubeService().registerFlatDiamondBonusProvider(PROVIDER_KEY, blockTreeService::flatDiamondBonus);
        zones.getCubeService().registerBlockCoinMultiplierProvider(PROVIDER_KEY, blockTreeService::blockCoinMultiplier);
        zones.getCubeService().registerDiamondChanceBoostProvider(PROVIDER_KEY, blockTreeService::diamondChanceBoost);
        zones.getPetCombatController().registerDoubleHitChanceProvider(PROVIDER_KEY, blockTreeService::doubleHitChance);
        zones.getPetCombatController().registerTripleHitChanceProvider(PROVIDER_KEY, blockTreeService::tripleHitChance);
    }

    public PlayerDataStore<BlockTreeProfile> getBlockStore() {
        return blockStore;
    }
}
