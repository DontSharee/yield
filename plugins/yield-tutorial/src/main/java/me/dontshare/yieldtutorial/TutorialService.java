package me.dontshare.yieldtutorial;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.player.PlayerProfile;
import me.dontshare.yieldcore.scoreboard.YieldScoreboardDisplay.ScoreboardContent;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldtutorial.data.CompletionTrigger;
import me.dontshare.yieldtutorial.data.TutorialContentLoader.TutorialContent;
import me.dontshare.yieldtutorial.data.TutorialStep;
import me.dontshare.yieldtutorial.npc.TutorialNpcManager;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Drives a player through {@code tutorial.yml}'s fixed step list - showing
 * each step's dialogue, listening for whichever {@link CompletionTrigger}
 * advances it, paying that step's reward, and despawning the guide once the
 * list is exhausted. Progress lives on yield-core's own {@link PlayerProfile}
 * (see its {@code tutorialStep}/{@code tutorialSkipped} fields) rather than
 * {@link PackPlayerProfile}, since the tutorial spans actions across both
 * yield-packs and yield-zones and this is the one player-record yield-core
 * itself owns independently of either.
 */
public final class TutorialService {

    private final Supplier<TutorialContent> content;
    private final PlayerDataStore<PlayerProfile> store;
    private final YieldPacks packs;
    private final TutorialNpcManager npcManager;

    public TutorialService(Supplier<TutorialContent> content, PlayerDataStore<PlayerProfile> store,
                            YieldPacks packs, TutorialNpcManager npcManager) {
        this.content = content;
        this.store = store;
        this.packs = packs;
        this.npcManager = npcManager;
        npcManager.setVisibilityFilter(this::isActive);
    }

    private boolean isActive(Player player) {
        PlayerProfile profile = store.getCached(player.getUniqueId());
        return profile != null && !profile.isTutorialSkipped() && profile.getTutorialStep() < content.get().steps().size();
    }

    /** Call on join - shows the player's current step's dialogue again (or nothing, if they've finished/skipped it). */
    public void onJoin(Player player) {
        PlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (isActive(player)) {
            showCurrentStep(player, profile);
        }
    }

    /** After an admin changed their tutorial state: the guide comes or goes to match, and a new current step is shown. */
    public void refresh(Player player) {
        PlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile != null && isActive(player)) {
            showCurrentStep(player, profile);
        } else {
            npcManager.hideFor(player);
        }
    }

    /** Right-click on the guide - a reminder of the current step, never an advance. */
    public void onNpcInteract(Player player) {
        PlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile != null && isActive(player)) {
            showCurrentStep(player, profile);
        }
    }

    /** Call whenever {@code player} performs an action that might advance their tutorial - a no-op if it doesn't match their current step. */
    public void onTrigger(Player player, CompletionTrigger trigger) {
        PlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null || !isActive(player)) {
            return;
        }
        List<TutorialStep> steps = content.get().steps();
        int index = profile.getTutorialStep();
        TutorialStep step = steps.get(index);
        if (step.completesOn() != trigger) {
            return;
        }

        int progress = profile.getTutorialStepProgress() + 1;
        if (progress < step.goal()) {
            // Not done yet - just bump the counter the checklist reads
            // live off the profile, no dialogue/reward until the goal's hit.
            profile.setTutorialStepProgress(progress);
            store.save(player.getUniqueId());
            return;
        }

        grantReward(player, step);
        profile.setTutorialStep(index + 1);
        profile.setTutorialStepProgress(0);
        store.save(player.getUniqueId());

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.02);

        if (index + 1 >= steps.size()) {
            complete(player);
        } else {
            showCurrentStep(player, profile);
        }
    }

    public void skip(Player player) {
        PlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.isTutorialSkipped()) {
            player.sendMessage(Text.parse("<gray>Already skipped.</gray>"));
            return;
        }
        profile.setTutorialSkipped(true);
        store.save(player.getUniqueId());
        npcManager.hideFor(player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
        player.sendMessage(Text.parse("<gray>Tutorial skipped.</gray>"));
    }

    private void complete(Player player) {
        npcManager.hideFor(player);
        player.sendMessage(Text.parse("<green><bold>Tutorial complete!</bold> You're ready to yield.</green>"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void grantReward(Player player, TutorialStep step) {
        if (step.rewardCoins() <= 0 && step.rewardDiamonds() <= 0) {
            return;
        }
        PackPlayerProfile packProfile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        if (step.rewardCoins() > 0) {
            packProfile.setCoins(packProfile.getCoins().add(BigInteger.valueOf(step.rewardCoins())));
        }
        if (step.rewardDiamonds() > 0) {
            packProfile.setDiamonds(packProfile.getDiamonds().add(BigInteger.valueOf(step.rewardDiamonds())));
        }
        packs.getPlayerStore().save(player.getUniqueId());

        StringBuilder reward = new StringBuilder();
        if (step.rewardCoins() > 0) {
            reward.append("<gold>+").append(step.rewardCoins()).append(" coins</gold>");
        }
        if (step.rewardDiamonds() > 0) {
            if (!reward.isEmpty()) {
                reward.append(" <gray>and</gray> ");
            }
            reward.append("<aqua>+").append(step.rewardDiamonds()).append(" diamonds</aqua>");
        }
        player.sendMessage(Text.parse("<green>Step complete!</green> " + reward));
    }

    private void showCurrentStep(Player player, PlayerProfile profile) {
        TutorialContent tutorial = content.get();
        List<TutorialStep> steps = tutorial.steps();
        int index = profile.getTutorialStep();
        TutorialStep step = steps.get(index);

        player.sendMessage(Text.parse("<#4BD9FF><bold>" + tutorial.npcName() + "</bold></#4BD9FF>"));
        for (String line : step.dialogue()) {
            player.sendMessage(Text.parse("<gray>" + line + "</gray>"));
        }
    }

    /**
     * The persistent sidebar checklist - registered with {@code
     * YieldScoreboardDisplay#setOverrideProvider}, so it's consulted fresh
     * every render (once a second, see that class) rather than being
     * pushed/pulled by tutorial state changes. Returns null (meaning "no
     * override, show the normal stat sidebar") once the tutorial is
     * finished or skipped, or for anyone who was never a fresh player at
     * all (their profile has no cached entry here yet).
     */
    public ScoreboardContent checklistFor(Player player) {
        PlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null || !isActive(player)) {
            return null;
        }
        List<TutorialStep> steps = content.get().steps();
        int current = profile.getTutorialStep();
        int currentProgress = profile.getTutorialStepProgress();

        List<String> lines = new ArrayList<>();
        lines.add(" ");
        lines.add("&7Getting Started:");
        for (int i = 0; i < steps.size(); i++) {
            TutorialStep step = steps.get(i);
            if (i < current) {
                lines.add("&a✔ &7" + step.label());
            } else if (i == current) {
                lines.add("&6■ &f" + step.label() + " &7" + currentProgress + "/" + step.goal());
            } else {
                lines.add("&c■ &7" + step.label() + " &8" + 0 + "/" + step.goal());
            }
        }
        lines.add(" ");
        lines.add("&7Type &f/tutorial skip &7to stop");
        return new ScoreboardContent("<#B57BFF><bold>Yield</bold>", lines);
    }
}
