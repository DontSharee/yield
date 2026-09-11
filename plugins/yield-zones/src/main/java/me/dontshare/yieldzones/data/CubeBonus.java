package me.dontshare.yieldzones.data;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * An optional roll applied on top of a cube's normal tier - independent of
 * which tier got picked, every spawn also rolls each configured bonus's own
 * {@code chance}; the highest-multiplier bonus that hits (if any) wins,
 * rendered as a colored glow around the cube (see OreCubeService) and
 * multiplying both its coin and XP payout. {@code color} is one of
 * vanilla's 16 named team colors - glow color is a hard vanilla limitation,
 * not an arbitrary hex value the way most of this project's other colors are.
 */
public record CubeBonus(String id, double chance, double multiplier, NamedTextColor color) {
}
