package me.dontshare.yieldpacks.pet;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.fusion.FusionTier;

/**
 * The one place that decides how a pet's "what is special about this one"
 * badges read, so the Bag, the world nametag, the pack reveal and chat
 * broadcasts never drift apart on it.
 * <p>
 * A pet can carry two independent badges at once, and they compose: its
 * fusion tier (see {@link FusionTier#tag()}) and Shiny. Huge deliberately
 * has no badge here - a Huge pet's own display name already begins with
 * "Huge", and it renders at multiple times normal size, so a third
 * indicator would just be noise.
 */
public final class PetLabels {

    /** Matches the warm gold the reveal and the Huge broadcast already use, and stays legible against every rarity color a name can be. */
    private static final String SHINY_STYLE = "<gradient:#FFF6B7:#FFD700:#FFF6B7>";

    private PetLabels() {
    }

    public static String shinyTag() {
        return SHINY_STYLE + "[" + Formatting.fancyFont("shiny") + "]";
    }

    /**
     * Every badge this pet should be shown with, space-separated, or null
     * when it has none - the shape every call site already expects from
     * {@code FusionTier#tag()}, so they can keep their existing
     * "null means no prefix" handling.
     */
    public static String tagsFor(ItemDefinition item, boolean shiny) {
        String fusion = item.fusionTier().tag();
        if (fusion == null) {
            return shiny ? shinyTag() : null;
        }
        return shiny ? shinyTag() + " " + fusion : fusion;
    }

    public static String tagsFor(ItemDefinition item, PetInstance pet) {
        return tagsFor(item, pet != null && pet.isShiny());
    }
}
