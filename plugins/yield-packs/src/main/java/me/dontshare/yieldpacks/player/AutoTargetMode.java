package me.dontshare.yieldpacks.player;

/** Only consulted while {@link SendMode#AUTO} is active - which live cube idle pets pick on their own, set via /autotarget. */
public enum AutoTargetMode {
    /** Attack whichever live cube is nearest. Default. */
    CLOSEST,
    /** Attack the highest-tier live cube - slower kills, better average loot. */
    STRONGEST,
    /** Attack the lowest-tier live cube - fast, high-volume clears. */
    WEAKEST
}
