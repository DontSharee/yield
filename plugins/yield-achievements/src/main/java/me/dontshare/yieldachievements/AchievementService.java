package me.dontshare.yieldachievements;

import me.dontshare.yieldachievements.data.AchievementDefinition;
import me.dontshare.yieldachievements.data.GameAction;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Specific, hand-authored one-off tasks - unlike {@link MilestoneService}'s
 * ladders, most of these have {@code goal() == 1} and complete in a single
 * trigger. Credits are granted the instant an achievement completes - no
 * separate claim screen for these (see {@code AchievementDefinition}).
 */
public final class AchievementService {

    private final Supplier<Map<String, AchievementDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;

    public AchievementService(Supplier<Map<String, AchievementDefinition>> content, PlayerDataStore<PackPlayerProfile> store) {
        this.content = content;
        this.store = store;
    }

    public void incrementProgress(Player player, GameAction action, long amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        boolean changed = false;
        for (AchievementDefinition def : content.get().values()) {
            if (def.trigger() != action || profile.getClaimedAchievementIds().contains(def.id())) {
                continue;
            }
            long current = profile.getAchievementProgress().getOrDefault(def.id(), 0L);
            if (current >= def.goal()) {
                continue;
            }
            long updated = Math.min(def.goal(), current + amount);
            profile.getAchievementProgress().put(def.id(), updated);
            changed = true;
            if (updated >= def.goal()) {
                grant(player, profile, def);
            }
        }
        if (changed) {
            store.save(player.getUniqueId());
        }
    }

    private void grant(Player player, PackPlayerProfile profile, AchievementDefinition def) {
        profile.getClaimedAchievementIds().add(def.id());
        profile.setCredits(profile.getCredits().add(def.rewardCredits()));
        player.sendMessage(Text.parse(
                "<#FFD700><bold>Achievement Complete!</bold></#FFD700> <white><name></white> <gray>-</gray> <gold>+<credits> credits</gold>",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(def.displayName())),
                Placeholder.unparsed("credits", Formatting.format(def.rewardCredits()))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.02);
    }

    public long progressOf(PackPlayerProfile profile, AchievementDefinition def) {
        return profile.getClaimedAchievementIds().contains(def.id())
                ? def.goal()
                : Math.min(def.goal(), profile.getAchievementProgress().getOrDefault(def.id(), 0L));
    }

    public boolean isComplete(PackPlayerProfile profile, AchievementDefinition def) {
        return profile.getClaimedAchievementIds().contains(def.id());
    }

    public enum UnlockResult {
        SUCCESS,
        ALREADY_COMPLETE,
        UNKNOWN
    }

    /** Admin/support override - completes and grants {@code achievementId} for {@code player} immediately, regardless of their actual progress. */
    public UnlockResult forceComplete(Player player, String achievementId) {
        AchievementDefinition def = content.get().get(achievementId);
        if (def == null) {
            return UnlockResult.UNKNOWN;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.getClaimedAchievementIds().contains(achievementId)) {
            return UnlockResult.ALREADY_COMPLETE;
        }
        profile.getAchievementProgress().put(achievementId, def.goal());
        grant(player, profile, def);
        store.save(player.getUniqueId());
        return UnlockResult.SUCCESS;
    }
}
