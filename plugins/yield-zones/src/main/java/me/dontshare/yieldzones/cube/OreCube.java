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
    private final int glowEntityId;
    private final UUID glowEntityUuid;
    private long currentHp;
    // Whether the temporary white "you're looking at this one" outline is
    // currently applied - toggled as a glow flag directly on this cube's own
    // body entity (see OreCubeService#spawnHighlight/despawnHighlight), not
    // a separate overlay entity - a separate always-full-size overlay used
    // to visually cover this cube's own hit-reaction shrink animation.
    // Never set true for a bonus cube (its own persistent colored glow
    // already stands out).
    private boolean highlighted;

    public OreCube(Location location, CubeTier tier, int blockEntityId, UUID blockEntityUuid, int textEntityId, CubeBonus bonus, int glowEntityId, UUID glowEntityUuid) {
        this.location = location;
        this.tier = tier;
        this.blockEntityId = blockEntityId;
        this.blockEntityUuid = blockEntityUuid;
        this.textEntityId = textEntityId;
        this.bonus = bonus;
        this.glowEntityId = glowEntityId;
        this.glowEntityUuid = glowEntityUuid;
        this.currentHp = tier.maxHp();
    }

    public Location location() {
        return location;
    }

    public CubeTier tier() {
        return tier;
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

    /** The packet-only, glowing block_display entity sitting on this cube - -1 if it has no bonus (see OreCubeService). */
    public int glowEntityId() {
        return glowEntityId;
    }

    /** This glow entity's UUID - needed to remove its scoreboard team entry on despawn. Null if it has no bonus. */
    public UUID glowEntityUuid() {
        return glowEntityUuid;
    }

    public long currentHp() {
        return currentHp;
    }

    /** Applies damage, clamped at 0 - never goes negative. Returns true once this brings it to (or below) 0. */
    public boolean damage(long amount) {
        currentHp = Math.max(0, currentHp - amount);
        return currentHp <= 0;
    }

    public boolean highlighted() {
        return highlighted;
    }

    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }
}
