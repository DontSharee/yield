package me.dontshare.yieldteams;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldteams.data.Team;
import me.dontshare.yieldteams.data.TeamUpgrade;
import me.dontshare.yieldteams.data.TeamUpgradeType;
import me.dontshare.yieldteams.data.TeamsContentLoader.TeamsContent;
import me.dontshare.yieldteams.event.TeamCreatedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Team membership, trophies, and upgrades. {@link PackPlayerProfile#getTeamId()}
 * is a denormalized cache of "which team is this player in" for fast
 * lookup - the {@link Team} document itself (via {@link #teamStore}) is
 * always the source of truth for membership. A kicked/disbanded OFFLINE
 * member's own {@code teamId} is deliberately left stale rather than
 * patched immediately (patching an offline player's document is a real
 * async round-trip for a rare operation) - {@link #reconcileOnJoin} instead
 * self-heals it the next time they actually log in, the same "check every
 * time, not just at the moment something changed" idiom already used for
 * cosmetic ownership elsewhere in this codebase.
 */
public final class TeamService {

    public enum CreateResult { SUCCESS, ALREADY_IN_TEAM, NAME_TAKEN, INVALID_LENGTH }
    public enum InviteResult { SUCCESS, NOT_IN_TEAM, NOT_LEADER, TARGET_IS_SELF, TARGET_ALREADY_IN_TEAM }
    public enum JoinResult { SUCCESS, NO_PENDING_INVITE, ALREADY_IN_TEAM, TEAM_GONE }
    public enum LeaveResult { SUCCESS, DISBANDED, NOT_IN_TEAM }
    public enum KickResult { SUCCESS, NOT_LEADER, CANNOT_KICK_SELF, TARGET_NOT_IN_TEAM }
    public enum DisbandResult { SUCCESS, NOT_LEADER }
    public enum UpgradeResult { SUCCESS, NOT_IN_TEAM, UNKNOWN_UPGRADE, MAXED, INSUFFICIENT_TROPHIES }

    private final TeamStore teamStore;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final Supplier<TeamsContent> content;
    // targetPlayerId -> teamId - ephemeral, never persisted; a new invite (or the target simply not being online to receive it) just overwrites/expires naturally.
    private final java.util.Map<UUID, UUID> pendingInvites = new ConcurrentHashMap<>();

    public TeamService(TeamStore teamStore, PlayerDataStore<PackPlayerProfile> playerStore, Supplier<TeamsContent> content) {
        this.teamStore = teamStore;
        this.playerStore = playerStore;
        this.content = content;
    }

    public Team teamOf(Player player) {
        PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
        return profile == null ? null : teamStore.get(profile.getTeamId());
    }

    /** Clears a stale teamId left over from an offline kick/disband - call on join before anything else touches this player's team. */
    public void reconcileOnJoin(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        UUID teamId = profile.getTeamId();
        if (teamId == null) {
            return;
        }
        Team team = teamStore.get(teamId);
        if (team == null || !team.getMemberIds().contains(player.getUniqueId())) {
            profile.setTeamId(null);
            playerStore.save(player.getUniqueId());
        }
    }

    public CreateResult create(Player player, String name) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        if (profile.getTeamId() != null) {
            return CreateResult.ALREADY_IN_TEAM;
        }
        if (name.length() < content.get().minNameLength() || name.length() > content.get().maxNameLength()) {
            return CreateResult.INVALID_LENGTH;
        }
        if (teamStore.nameTaken(name)) {
            return CreateResult.NAME_TAKEN;
        }
        Team team = new Team(UUID.randomUUID(), name, player.getUniqueId());
        teamStore.create(team);
        profile.setTeamId(team.getId());
        playerStore.save(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new TeamCreatedEvent(player, team));
        return CreateResult.SUCCESS;
    }

    public InviteResult invite(Player leader, Player target) {
        Team team = teamOf(leader);
        if (team == null) {
            return InviteResult.NOT_IN_TEAM;
        }
        if (!team.getLeaderId().equals(leader.getUniqueId())) {
            return InviteResult.NOT_LEADER;
        }
        if (target.getUniqueId().equals(leader.getUniqueId())) {
            return InviteResult.TARGET_IS_SELF;
        }
        PackPlayerProfile targetProfile = playerStore.getCached(target.getUniqueId());
        if (targetProfile != null && targetProfile.getTeamId() != null) {
            return InviteResult.TARGET_ALREADY_IN_TEAM;
        }
        pendingInvites.put(target.getUniqueId(), team.getId());
        return InviteResult.SUCCESS;
    }

    /** Accepts whichever invite is currently pending for this player (the most recent one, if more than one arrived). */
    public JoinResult acceptInvite(Player player) {
        UUID teamId = pendingInvites.remove(player.getUniqueId());
        if (teamId == null) {
            return JoinResult.NO_PENDING_INVITE;
        }
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        if (profile.getTeamId() != null) {
            return JoinResult.ALREADY_IN_TEAM;
        }
        Team team = teamStore.get(teamId);
        if (team == null) {
            return JoinResult.TEAM_GONE;
        }
        team.getMemberIds().add(player.getUniqueId());
        teamStore.save(team);
        profile.setTeamId(teamId);
        playerStore.save(player.getUniqueId());
        return JoinResult.SUCCESS;
    }

    public LeaveResult leave(Player player) {
        Team team = teamOf(player);
        if (team == null) {
            return LeaveResult.NOT_IN_TEAM;
        }
        team.getMemberIds().remove(player.getUniqueId());
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        profile.setTeamId(null);
        playerStore.save(player.getUniqueId());

        if (team.getMemberIds().isEmpty()) {
            teamStore.delete(team);
            return LeaveResult.DISBANDED;
        }
        if (team.getLeaderId().equals(player.getUniqueId())) {
            team.setLeaderId(team.getMemberIds().get(0));
        }
        teamStore.save(team);
        return LeaveResult.SUCCESS;
    }

    public KickResult kick(Player leader, UUID targetId) {
        Team team = teamOf(leader);
        if (team == null || !team.getLeaderId().equals(leader.getUniqueId())) {
            return KickResult.NOT_LEADER;
        }
        if (targetId.equals(leader.getUniqueId())) {
            return KickResult.CANNOT_KICK_SELF;
        }
        if (!team.getMemberIds().remove(targetId)) {
            return KickResult.TARGET_NOT_IN_TEAM;
        }
        teamStore.save(team);
        Player targetPlayer = Bukkit.getPlayer(targetId);
        if (targetPlayer != null) {
            PackPlayerProfile targetProfile = playerStore.getCached(targetId);
            if (targetProfile != null) {
                targetProfile.setTeamId(null);
                playerStore.save(targetId);
            }
        }
        return KickResult.SUCCESS;
    }

    public DisbandResult disband(Player leader) {
        Team team = teamOf(leader);
        if (team == null || !team.getLeaderId().equals(leader.getUniqueId())) {
            return DisbandResult.NOT_LEADER;
        }
        for (UUID memberId : team.getMemberIds()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member == null) {
                continue;
            }
            PackPlayerProfile memberProfile = playerStore.getCached(memberId);
            if (memberProfile != null) {
                memberProfile.setTeamId(null);
                playerStore.save(memberId);
            }
        }
        teamStore.delete(team);
        return DisbandResult.SUCCESS;
    }

    public boolean depositTrophies(Player player, int amount) {
        Team team = teamOf(player);
        if (team == null || amount <= 0) {
            return false;
        }
        team.setTrophyBalance(team.getTrophyBalance().add(BigInteger.valueOf(amount)));
        teamStore.save(team);
        return true;
    }

    public UpgradeResult buyUpgrade(Player player, String upgradeId) {
        Team team = teamOf(player);
        if (team == null) {
            return UpgradeResult.NOT_IN_TEAM;
        }
        TeamUpgrade upgrade = content.get().upgrades().get(upgradeId);
        if (upgrade == null) {
            return UpgradeResult.UNKNOWN_UPGRADE;
        }
        int level = team.getUpgradeLevels().getOrDefault(upgradeId, 0);
        if (level >= upgrade.maxLevel()) {
            return UpgradeResult.MAXED;
        }
        BigInteger cost = upgrade.costs().get(level);
        if (team.getTrophyBalance().compareTo(cost) < 0) {
            return UpgradeResult.INSUFFICIENT_TROPHIES;
        }
        team.setTrophyBalance(team.getTrophyBalance().subtract(cost));
        team.getUpgradeLevels().merge(upgradeId, 1, Integer::sum);
        teamStore.save(team);
        return UpgradeResult.SUCCESS;
    }

    /** The sum of every owned upgrade level's contribution of this type, for the player's current team (0 if they're not on one) - consumed as {@code 1.0 + totalBonus(...)} alongside every other multiplier source. */
    public double totalBonus(PackPlayerProfile profile, TeamUpgradeType type) {
        Team team = teamStore.get(profile.getTeamId());
        if (team == null) {
            return 0.0;
        }
        double total = 0.0;
        for (TeamUpgrade upgrade : content.get().upgrades().values()) {
            if (upgrade.type() != type) {
                continue;
            }
            int level = team.getUpgradeLevels().getOrDefault(upgrade.id(), 0);
            total += level * upgrade.valuePerLevel();
        }
        return total;
    }
}
