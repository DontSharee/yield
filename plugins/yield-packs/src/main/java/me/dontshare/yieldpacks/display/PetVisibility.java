package me.dontshare.yieldpacks.display;

/** A player's own preference for what equipped-pet displays they personally see - set via /petvisibility. */
public enum PetVisibility {
    /** Everyone's pets, including your own. Default. */
    ALL,
    /** Only your own pets. */
    MINE_ONLY,
    /** Everyone's pets except your own. */
    OTHERS_ONLY,
    /** No pets at all. */
    NONE
}
