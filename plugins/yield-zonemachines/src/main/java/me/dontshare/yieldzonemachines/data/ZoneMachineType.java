package me.dontshare.yieldzonemachines.data;

/**
 * What a physical zone machine actually does - see {@code ZoneMachineService
 * #attemptUse}. REBIRTH fills a real gap: before this plugin, a rebirth was
 * only reachable via {@code /rebirth}. CANDY (a straight coins purchase) and
 * DIAMOND_EXCHANGE (coins -> diamonds) were both removed by design - see the plan
 * this enum's git history came from for why, if it matters later.
 */
public enum ZoneMachineType {
    REBIRTH,
    /**
     * Smack to open a small drag-and-drop menu (see {@code CandyApplyGui}) -
     * drop a candy into its one slot to feed it straight to your strongest
     * equipped pet. The candy-in-hand gesture already worked (see
     * PetLevelingService#feedCandy, still used by /bag), this is just a
     * second, discoverable, in-world path to it - now drag-and-drop instead
     * of "hold the item, then smack", matching every other slot-based menu
     * in the game (Forge, Enchants).
     */
    CANDY_APPLY
}
