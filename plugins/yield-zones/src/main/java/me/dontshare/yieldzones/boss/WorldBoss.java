package me.dontshare.yieldzones.boss;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One currently-alive boss instance. Unlike {@code OreCube} (a per-player
 * illusion), this is real, shared state - one {@link #hp} pool everyone's
 * pets chip away at together, and one real {@link BlockDisplay}/barrier
 * cluster every player sees and can click, not a packet-only trick.
 */
public final class WorldBoss {

    private final WorldBossDefinition definition;
    private BlockDisplay displayEntity;
    private final List<Location> barrierLocations;
    private final long spawnedAtMillis;
    private final Map<UUID, Long> damageByPlayer = new HashMap<>();
    private long hp;
    private BossBar bossBar;
    /** The floating name + health bar above the boss - see WorldBossService#spawnNametag. */
    private TextDisplay nametag;
    /** HP changed since the bar and nametag were last redrawn - see WorldBossService#tick. */
    private boolean dirty;

    public WorldBoss(WorldBossDefinition definition, BlockDisplay displayEntity, List<Location> barrierLocations) {
        this.definition = definition;
        this.displayEntity = displayEntity;
        this.barrierLocations = new ArrayList<>(barrierLocations);
        this.hp = definition.maxHp();
        this.spawnedAtMillis = System.currentTimeMillis();
    }

    public WorldBossDefinition definition() {
        return definition;
    }

    public BlockDisplay displayEntity() {
        return displayEntity;
    }

    /** Swapped in when the old one was discarded with its chunk - see WorldBossService#ensureDisplays. */
    public void setDisplayEntity(BlockDisplay displayEntity) {
        this.displayEntity = displayEntity;
    }

    public List<Location> barrierLocations() {
        return barrierLocations;
    }

    public Location center() {
        return definition.location();
    }

    public long hp() {
        return hp;
    }

    public boolean isDead() {
        return hp <= 0;
    }

    public long spawnedAtMillis() {
        return spawnedAtMillis;
    }

    /** Returns the new HP after applying this hit (clamped at 0, never negative). */
    public long damage(long amount) {
        hp = Math.max(0, hp - amount);
        return hp;
    }

    public void addContribution(UUID playerId, long amount) {
        damageByPlayer.merge(playerId, amount, Long::sum);
    }

    public Map<UUID, Long> damageByPlayer() {
        return damageByPlayer;
    }

    public long totalDamageDealt() {
        return damageByPlayer.values().stream().mapToLong(Long::longValue).sum();
    }

    public TextDisplay nametag() {
        return nametag;
    }

    public void setNametag(TextDisplay nametag) {
        this.nametag = nametag;
    }

    public boolean dirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /** Where the boss's model actually is: the middle of its size-block cube, whose corner is the location minus half the size, rounded down. */
    public Location visualCenter() {
        int size = definition.size();
        int radius = size / 2;
        Location at = definition.location();
        return new Location(at.getWorld(), at.getBlockX() - radius + size / 2.0,
                at.getBlockY() - radius + size / 2.0, at.getBlockZ() - radius + size / 2.0);
    }

    /** The Y of the boss's bottom face - its floor. */
    public double floorY() {
        return definition.location().getBlockY() - definition.size() / 2;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public void setBossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }
}
