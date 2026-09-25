package me.dontshare.yieldzones.cube;

import me.dontshare.yieldzones.data.CubeBonus;
import me.dontshare.yieldzones.data.CubeTier;
import org.bukkit.Location;

import java.util.UUID;

/** One landed, live ore cube a specific player can see and fight. */
public final class OreCube {

    private final Location location;
    private final CubeTier tier;
    private final int blockEntityId;
    private final UUID blockEntityUuid;
    private final int textEntityId;
    private final CubeBonus bonus;
    /** The invisible shulker giving a giant cube its full-size collision, or -1 for an ordinary cube - see OreCubeService#spawnCollision. */
    private int collisionEntityId = -1;
    private long currentHp;
    // Whether the temporary white "you're looking at this one" outline is
    // currently applied - toggled as a glow flag directly on this cube's own
    // body entity (see OreCubeService#spawnHighlight/despawnHighlight), not
    // a separate overlay entity - a separate always-full-size overlay used
    // to visually cover this cube's own hit-reaction shrink animation.
    // Never set true for a bonus cube (its own persistent colored glow
    // already stands out).
    private boolean highlighted;
    /** How many of the mid-fight payouts this cube has already dropped, and what they came to - the kill pays the rest. See OreCubeService#payChips. */
    private int chipsPaid;
    private long chippedCoins;
    private long chippedDiamonds;
    /** 1 for the zone's common cube, 2, 3... by HP - 0 for a cube outside the ladder (treasure). See OreCubeService#rankCube. */
    private int tierRank;
    /** How much likelier this cube is than the zone average to pay out diamonds - see OreCubeService#rankCube. */
    private double diamondChanceMultiplier = 1.0;
    /** How much likelier its rare drops (enchant books, candy) are. */
    private double rareDropMultiplier = 1.0;

    public OreCube(Location location, CubeTier tier, int blockEntityId, UUID blockEntityUuid, int textEntityId, CubeBonus bonus) {
        this.location = location;
        this.tier = tier;
        this.blockEntityId = blockEntityId;
        this.blockEntityUuid = blockEntityUuid;
        this.textEntityId = textEntityId;
        this.bonus = bonus;
        this.currentHp = tier.maxHp();
    }

    public Location location() {
        return location;
    }

    public CubeTier tier() {
        return tier;
    }

    /** Edge length in blocks - 1 for an ordinary cube, more for a giant one (see {@link CubeTier#size}). */
    public float size() {
        return tier.size();
    }

    /**
     * The middle of the cube as it actually renders: centred on its block
     * column horizontally and standing on the floor, so a 1.5-block safe's
     * middle is 0.75 up, not 0.5. Where pets aim, where hit sparks and
     * damage numbers appear, and where the death burst goes all come from
     * here, so a giant cube gets hit in the middle rather than the shins.
     */
    public Location center() {
        return location.clone().add(0.5, size() / 2.0, 0.5);
    }

    /** The packet-only block_display entity that's this cube's entire visible body - see FakeFallingBlock. Animated on hit, destroyed when the cube goes away. */
    public int blockEntityId() {
        return blockEntityId;
    }

    /** This body entity's own UUID - needed to add/remove it from the highlight scoreboard team, since vanilla resolves glow color by matching an entity's UUID against team membership. */
    public UUID blockEntityUuid() {
        return blockEntityUuid;
    }

    /** The packet-only text_display entity floating above this cube, showing its HP - see OreCubeService. */
    public int textEntityId() {
        return textEntityId;
    }

    /** Null for a plain cube - see OreCubeService's glow/payout handling. */
    public CubeBonus bonus() {
        return bonus;
    }

    public int collisionEntityId() {
        return collisionEntityId;
    }

    public void setCollisionEntityId(int collisionEntityId) {
        this.collisionEntityId = collisionEntityId;
    }

    public long currentHp() {
        return currentHp;
    }

    /** Applies damage, clamped at 0 - never goes negative. Returns true once this brings it to (or below) 0. */
    public boolean damage(long amount) {
        currentHp = Math.max(0, currentHp - amount);
        return currentHp <= 0;
    }

    public int chipsPaid() {
        return chipsPaid;
    }

    public long chippedCoins() {
        return chippedCoins;
    }

    public long chippedDiamonds() {
        return chippedDiamonds;
    }

    public void recordChip(long coins, long diamonds) {
        chipsPaid++;
        chippedCoins += coins;
        chippedDiamonds += diamonds;
    }

    public int tierRank() {
        return tierRank;
    }

    public double diamondChanceMultiplier() {
        return diamondChanceMultiplier;
    }

    public double rareDropMultiplier() {
        return rareDropMultiplier;
    }

    public void setRarity(int tierRank, double diamondChanceMultiplier, double rareDropMultiplier) {
        this.tierRank = tierRank;
        this.diamondChanceMultiplier = diamondChanceMultiplier;
        this.rareDropMultiplier = rareDropMultiplier;
    }

    public boolean highlighted() {
        return highlighted;
    }

    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }
}
