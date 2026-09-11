package me.dontshare.yieldachievements.data;

/**
 * Every in-game action an achievement or milestone category can be
 * configured to track - mirrors yield-quests' own GameAction vocabulary
 * (same underlying cross-plugin events, just a separate copy since
 * achievements/milestones are a permanent progression system, not a daily-
 * resetting one - see {@code AchievementEventListener}/{@code
 * MilestoneEventListener}'s shared bridge). Adding a new trackable action
 * means adding both a value here and a fire call in that listener -
 * everything else (which achievements/categories use it) is pure config.
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
    BUY_SKILL_NODE,
    CREATE_TEAM,
    KILL_WORLD_BOSS,
    DEAL_BOSS_DAMAGE
}
