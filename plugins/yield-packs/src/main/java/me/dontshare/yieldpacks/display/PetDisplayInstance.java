package me.dontshare.yieldpacks.display;

/**
 * One equipped pet's client-side entity pair (floating item + its name/level
 * label), tracked per owner. {@code level} is a snapshot taken when this
 * instance was (re)built (equip/unequip, or a level-up - see
 * {@code PetDisplayService#refreshLevelLabel}), not a live read - the
 * nametag only needs to be accurate at the moments something about it
 * actually changed.
 */
public record PetDisplayInstance(String itemId, int level, boolean shiny, int itemEntityId, int textEntityId) {
}
