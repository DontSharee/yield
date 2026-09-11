package me.dontshare.yieldpacks.player;

/** Only consulted while {@link SendMode#MANUAL} is active - what a click on a cube actually sends. Toggled from the Settings GUI, not a command. */
public enum AttackMode {
    /** Each click sends exactly the next pet in rotation, not the whole squad. Default. */
    SINGLE,
    /** Each click sends every currently-idle equipped pet at once. */
    ALL
}
