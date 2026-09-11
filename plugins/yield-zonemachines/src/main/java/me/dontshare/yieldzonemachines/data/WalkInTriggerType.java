package me.dontshare.yieldzonemachines.data;

/**
 * What walking into this ring actually opens - see {@code
 * WalkInTriggerDisplay#handleEnter}. None of these spend anything
 * themselves on entry - they just open the real GUI (Fusion, filtered to
 * one target tier; Enchants) where the actual action happens, physically
 * relocating an entry point that already existed rather than duplicating
 * its logic. RANKUP was removed from here - Ranks are bought with gems at
 * any time, so gating that behind one physical zone was an odd friction
 * point; it's a plain "/rankup" command now (see {@code RankupCommand}),
 * meant to be wired to an NPC at spawn instead.
 */
public enum WalkInTriggerType {
    GOLDEN_FUSION,
    RAINBOW_FUSION,
    DARK_MATTER_FUSION,
    ENCHANTS
}
