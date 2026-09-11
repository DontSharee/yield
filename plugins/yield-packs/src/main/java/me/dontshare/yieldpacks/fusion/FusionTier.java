package me.dontshare.yieldpacks.fusion;

import me.dontshare.yieldcore.text.Formatting;

import java.util.Locale;

/**
 * A pet's fusion tier - 3 of one tier combine into 1 of the next (see
 * {@code FusionService}). Every tier past {@code NORMAL} is a synthetic,
 * auto-generated {@code ItemDefinition} rather than something defined in
 * packs.yml - see {@code PackContentLoader}'s per-item synthesis loop.
 * <p>
 * A fused pet's own name is never gradiented - {@code PackContentLoader}
 * deliberately gives every tier of a pet the exact same plain
 * {@code displayName()} as its base. Only {@link #tag()} carries the
 * gradient, and it's up to each display site (Bag icon, world nametag,
 * fusion chat message, etc.) to prepend it where a fused pet needs calling
 * out - the name itself always stays whatever plain/rarity color it
 * already was.
 */
public enum FusionTier {
    NORMAL(1.0, null),
    GOLDEN(3.0, "<gradient:#FFF6B7:#FFD700:#FDC830>"),
    RAINBOW(9.0, "<gradient:#FF5F6D:#FFC371:#FFF95B:#63FF8F:#4FACFE:#C471F5:#FF5F6D>"),
    DARK_MATTER(27.0, "<gradient:#B026FF:#2B0B3F:#000000>");

    private final double damageMultiplier;
    private final String nameStyle;

    FusionTier(double damageMultiplier, String nameStyle) {
        this.damageMultiplier = damageMultiplier;
        this.nameStyle = nameStyle;
    }

    /** Cumulative multiplier off the base (unfused) pet's own damage stat. */
    public double damageMultiplier() {
        return damageMultiplier;
    }

    /** The MiniMessage tag(s) this tier wraps a pet's (already color-stripped) base name in - null for {@link #NORMAL}. */
    public String nameStyle() {
        return nameStyle;
    }

    /** The item id this tier of {@code baseItemId} lives under - unchanged for {@link #NORMAL}, suffixed otherwise. */
    public String idFor(String baseItemId) {
        return this == NORMAL ? baseItemId : baseItemId + "_" + name().toLowerCase(Locale.ROOT);
    }

    /** "[GOLDEN]" etc. - small-caps, wrapped in this tier's own gradient - or null for {@link #NORMAL}, which has no tag at all. */
    public String tag() {
        if (this == NORMAL) {
            return null;
        }
        String word = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return nameStyle + "[" + Formatting.fancyFont(word) + "]";
    }

    /** The tier 3 of this one fuse into - null if already maxed ({@link #DARK_MATTER}). */
    public FusionTier next() {
        return switch (this) {
            case NORMAL -> GOLDEN;
            case GOLDEN -> RAINBOW;
            case RAINBOW -> DARK_MATTER;
            case DARK_MATTER -> null;
        };
    }
}
