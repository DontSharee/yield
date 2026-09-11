package me.dontshare.yieldskilltree.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired from {@code SkillTreeService#buyLevel} and {@code #autoBuy} whenever a node's level actually increases. */
public final class SkillNodeBoughtEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String treeId;
    private final String nodeId;
    private final int newLevel;

    public SkillNodeBoughtEvent(Player player, String treeId, String nodeId, int newLevel) {
        this.player = player;
        this.treeId = treeId;
        this.nodeId = nodeId;
        this.newLevel = newLevel;
    }

    public Player getPlayer() {
        return player;
    }

    public String getTreeId() {
        return treeId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getNewLevel() {
        return newLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
