package me.dontshare.yieldskilltree.data;

/**
 * What a node's value-formula output feeds into - see
 * {@code SkillTreeService#totalFor}, which sums every owned node of a
 * given type (across both trees) into one running total, and each
 * integration point below decides how to consume that total:
 * <ul>
 *   <li>{@code DAMAGE_MULTIPLIER}/{@code COIN_MULTIPLIER}/{@code LUCK_MULTIPLIER}/
 *       {@code ROLL_SPEED_MULTIPLIER} - consumed as {@code 1.0 + total}.</li>
 *   <li>{@code EQUIP_SLOTS} - consumed as {@code baseValue + total} (rounded to an int).</li>
 * </ul>
 */
public enum NodeType {
    DAMAGE_MULTIPLIER,
    COIN_MULTIPLIER,
    LUCK_MULTIPLIER,
    ROLL_SPEED_MULTIPLIER,
    EQUIP_SLOTS
}
