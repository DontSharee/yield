package me.dontshare.yieldpacks.rank;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Registered by yield-quests once it enables (see {@code RankQuestService
 * #viewsFor}) - lets {@code RankupGui} show the player's 3 active Rank
 * Quests inline, without yield-packs depending on yield-quests (the
 * dependency arrow only ever runs the other way). {@code null} until
 * yield-quests registers itself, so {@code RankupGui} renders without a
 * quest section if that plugin isn't installed/enabled.
 */
public interface RankQuestSource {

    List<RankQuestView> activeQuests(Player player);

    record RankQuestView(String displayName, Material icon, long progress, long goal, long rewardStars, boolean completed) {
    }
}
