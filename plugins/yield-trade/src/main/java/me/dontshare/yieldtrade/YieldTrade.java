package me.dontshare.yieldtrade;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldtrade.command.TradeCommand;
import me.dontshare.yieldtrade.command.WithdrawCommand;
import me.dontshare.yieldtrade.currency.CurrencyNoteItem;
import me.dontshare.yieldtrade.currency.CurrencyNoteRedeemListener;
import me.dontshare.yieldtrade.currency.CurrencyNoteService;
import me.dontshare.yieldtrade.currency.CurrencyNoteStore;
import me.dontshare.yieldtrade.gui.TradeGuiListener;
import me.dontshare.yieldtrade.session.TradeService;
import me.dontshare.yieldtrade.store.TradeClaimDeliveryListener;
import me.dontshare.yieldtrade.store.TradeClaimStore;
import me.dontshare.yieldtrade.store.TradeEscrowStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class YieldTrade extends JavaPlugin {

    private TradeService tradeService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        DatabaseManager databaseManager = core.getDatabaseManager();

        TradeEscrowStore escrowStore = new TradeEscrowStore(databaseManager);
        TradeClaimStore claimStore = new TradeClaimStore(databaseManager);

        // Anything still in escrow belongs to a run that didn't get to finish
        // (a crash, a kill -9) - turn it into claims before any new trade can
        // write over it.
        databaseManager.supplyAsync(() -> escrowStore.recoverOrphans(claimStore))
                .thenAccept(recovered -> {
                    if (recovered > 0) {
                        getLogger().info("Recovered " + recovered + " interrupted trade(s); items queued for return.");
                    }
                });

        tradeService = new TradeService(this, packs.getPlayerStore(), databaseManager, escrowStore, claimStore);
        tradeService.startConfirmTicker();

        CurrencyNoteStore noteStore = new CurrencyNoteStore(databaseManager);
        CurrencyNoteItem noteItem = new CurrencyNoteItem(this);
        CurrencyNoteService noteService = new CurrencyNoteService(this, databaseManager, noteStore, noteItem, packs.getPlayerStore());

        core.getListenerManager().register(new TradeGuiListener(tradeService, this));
        core.getListenerManager().register(new CurrencyNoteRedeemListener(noteService));
        core.getListenerManager().register(new TradeClaimDeliveryListener(this, databaseManager, claimStore));

        CommandManager.register(this, TradeCommand.build(tradeService), "Trade items with another player", List.of());
        CommandManager.register(this, WithdrawCommand.build(noteService), "Withdraw currency as a tradeable note", List.of());
    }

    @Override
    public void onDisable() {
        // Offered items live only in memory, so a shutdown that left a trade
        // open would strand them - hand everything back before stopping.
        if (tradeService != null) {
            tradeService.cancelAll("<red>Trade cancelled - the server is restarting.</red>");
        }
    }

    public TradeService getTradeService() {
        return tradeService;
    }
}
