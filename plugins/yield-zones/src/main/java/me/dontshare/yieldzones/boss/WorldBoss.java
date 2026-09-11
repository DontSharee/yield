package me.dontshare.yieldzones.boss;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;

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
    private final BlockDisplay displayEntity;
    private final List<Location> barrierLocations;
    private final long spawnedAtMillis;
    private final Map<UUID, Long> damageByPlayer = new HashMap<>();
    private long hp;
    private BossBar bossBar;

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

    public BossBar bossBar() {
        return bossBar;
    }

    public void setBossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }
}
