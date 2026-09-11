package me.dontshare.yieldpacks.player;

/** Whether equipped pets fight on their own, or only when the player sends them - set via /sendmode. */
public enum SendMode {
    /** Pets never fight until sent - see {@link AttackMode} for how a click sends them. Default. */
    MANUAL,
    /** Pets fight automatically per {@link AutoTargetMode} - today's original, fully hands-off behavior. */
    AUTO
}
