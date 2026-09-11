package me.dontshare.yieldtutorial.data;

/** The small, fixed set of in-game actions a tutorial step can advance past - see {@code tutorial.yml}'s own "completes-on" key. */
public enum CompletionTrigger {
    OPEN_PACK,
    EQUIP_PET,
    ENTER_ZONE,
    KILL_CUBE
}
