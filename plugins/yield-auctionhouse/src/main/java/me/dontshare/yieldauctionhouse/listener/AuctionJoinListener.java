package me.dontshare.yieldauctionhouse.listener;

import me.dontshare.yieldauctionhouse.AuctionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Auto-claims everything pending in a player's Collection Box the moment they join - so proceeds/returns/purchases show up without needing to know to look. */
public final class AuctionJoinListener implements Listener {

    private final AuctionService service;

    public AuctionJoinListener(AuctionService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.claimAll(event.getPlayer());
    }
}
