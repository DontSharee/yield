package me.dontshare.yieldblocktree.listener;

import me.dontshare.yieldblocktree.BlockTreeFeedback;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Bridges yield-zones' own kill event into blocktree progress - yield-zones needs no awareness of this module at all. See BlockTreeFeedback for what actually happens on a break (tier-cross announcement, shard-drop roll) - the same reaction yield-mining's own direct-mining breaks trigger too. */
public final class BlockTreeProgressListener implements Listener {

    private final BlockTreeFeedback feedback;

    public BlockTreeProgressListener(BlockTreeFeedback feedback) {
        this.feedback = feedback;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        feedback.recordBreakAndAnnounce(event.getPlayer(), event.getTier().material());
    }
}
