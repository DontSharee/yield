package me.dontshare.yieldquests.data;

/**
 * Every in-game action a quest can be configured to track - the full set
 * {@code QuestEventListener} actually wires up, one per real cross-plugin
 * event already fired somewhere in the codebase. Adding a new trackable
 * action means adding both a value here and a handler in
 * {@code QuestEventListener} - everything else (which categories/tiers use
 * it) is pure config, no further code changes needed.
 */
public enum GameAction {
    OPEN_PACK,
    OBTAIN_PET,
    EQUIP_PET,
    UNEQUIP_PET,
    FUSE_PET,
    FEED_CANDY,
    LEVEL_UP_PET,
    KILL_CUBE,
    EARN_COINS,
    ENTER_ZONE,
    UNLOCK_ZONE,
    REBIRTH,
    PRESTIGE,
    BUY_SKILL_NODE
}
