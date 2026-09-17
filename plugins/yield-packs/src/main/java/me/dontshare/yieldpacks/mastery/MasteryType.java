package me.dontshare.yieldpacks.mastery;

/**
 * The 4 mastery tracks - Yield's actual core loops (a deliberate reduction
 * from PS99's 14). {@code REFINERY} ("Pet Refinery") is earned via Pet
 * Enchanting Table rolls - deliberately NOT named "Enchants", which would
 * collide with two other, unrelated systems already using that word
 * (player-wide Enchant Books, and the Pet Enchanting Table itself).
 */
public enum MasteryType {
    PACKS,
    REFINERY,
    MINING,
    COMBAT
}
