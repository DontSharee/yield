package me.dontshare.yieldpacks.display;

/** One equipped pet's client-side entity pair (floating item + its name/value label), tracked per owner. */
public record PetDisplayInstance(String itemId, int itemEntityId, int textEntityId) {
}
