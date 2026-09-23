package me.dontshare.yieldpacks.player;

/** Whether equipped pets fight on their own, or only when the player sends them - set via /sendmode. */
public enum SendMode {
    /** Pets never fight until sent - see {@link AttackMode} for how a click sends them. Default. */
    MANUAL,
    /** Pets fight automatically per {@link AutoTargetMode} - today's original, fully hands-off behavior. */
    AUTO;

    /** The bought Auto Send perk - checked by EVERY way into AUTO (the Bag toggle and /sendmode alike). */
    public static final String AUTO_PERMISSION = "yieldpacks.automode";
    /** Premium Auto Send - faster re-engage; see the combat controller. */
    public static final String PREMIUM_AUTO_PERMISSION = "yieldpacks.automode.premium";
}
