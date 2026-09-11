package me.dontshare.yieldauctionhouse;

import me.dontshare.yieldauctionhouse.command.AuctionAdminCommand;
import me.dontshare.yieldauctionhouse.command.AuctionCommand;
import me.dontshare.yieldauctionhouse.data.AuctionConfig;
import me.dontshare.yieldauctionhouse.data.AuctionConfigLoader;
import me.dontshare.yieldauctionhouse.gui.AuctionBrowseGui;
import me.dontshare.yieldauctionhouse.gui.AuctionCollectionBoxGui;
import me.dontshare.yieldauctionhouse.gui.AuctionMyListingsGui;
import me.dontshare.yieldauctionhouse.listener.AuctionJoinListener;
import me.dontshare.yieldauctionhouse.store.AuctionClaimStore;
import me.dontshare.yieldauctionhouse.store.AuctionListingStore;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class YieldAuctionHouse extends JavaPlugin {

    private AuctionConfigLoader configLoader;
    private volatile AuctionConfig config;
    private AuctionService auctionService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        configLoader = new AuctionConfigLoader(this);
        config = configLoader.load();

        AuctionListingStore listingStore = new AuctionListingStore(core.getDatabaseManager());
        AuctionClaimStore claimStore = new AuctionClaimStore(core.getDatabaseManager());
        auctionService = new AuctionService(this, core.getDatabaseManager(), listingStore, claimStore, packs, () -> config);

        AuctionMyListingsGui myListingsGui = new AuctionMyListingsGui(this, core.getDatabaseManager(), listingStore, auctionService, core.getGuiManager());
        AuctionCollectionBoxGui collectionBoxGui = new AuctionCollectionBoxGui(this, auctionService, core.getGuiManager());
        AuctionBrowseGui browseGui = new AuctionBrowseGui(this, core.getDatabaseManager(), listingStore, auctionService,
                core.getGuiManager(), () -> config, myListingsGui, collectionBoxGui);
        myListingsGui.setBrowseGui(browseGui);
        collectionBoxGui.setBrowseGui(browseGui);
        browseGui.start();

        Bukkit.getPluginManager().registerEvents(new AuctionJoinListener(auctionService), this);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, auctionService::runExpirySweep, 100L, config.expirySweepIntervalTicks());

        CommandManager.register(this, AuctionCommand.build(this, browseGui, auctionService), "Open the Auction House", List.of("ah"));
        core.getAdminCommandRegistry().register(AuctionAdminCommand.build(this, auctionService));
    }

    /** Re-reads auctionhouse.yml - existing AuctionService instance keeps working against the same, now-updated config supplier. */
    public void reloadContent() {
        config = configLoader.load();
    }

    public AuctionService getAuctionService() {
        return auctionService;
    }
}
