package me.dontshare.yieldpacks.mastery;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * 4 passive per-activity XP tracks (see {@link MasteryType}) - genuinely
 * new to this codebase (nothing like a perpetual, purchase-free per-
 * activity grind existed before). Level is always derived fresh from
 * accumulated XP against the current curve (same "never cache a
 * derived value" philosophy {@code PlayerLevelingService} already
 * established), so a balance change in masteries.yml applies instantly
 * to every already-leveled player. Deliberately no title/sound spam per
 * level-up - packs/mining/combat happen far too often for that, so a
 * level-up only flashes a quiet action-bar line.
 * <p>
 * Each track unlocks a list of discrete, named {@link MasteryPerk}s at
 * specific levels (PS99-style) rather than one smooth bonus - see
 * {@link #sumStat}, the one method every stat-multiplier provider
 * registered elsewhere (see {@code YieldPacks#onEnable}) calls.
 */
public final class MasteryService {

    private final Supplier<MasteryConfig> config;
    private final PlayerDataStore<PackPlayerProfile> store;

    public MasteryService(Supplier<MasteryConfig> config, PlayerDataStore<PackPlayerProfile> store) {
        this.config = config;
        this.store = store;
    }

    /** How much XP {@code level} needs to reach {@code level + 1}. */
    public long xpForLevel(int level) {
        MasteryConfig cfg = config.get();
        return Math.round(cfg.curveBase() * Math.pow(Math.max(1, level), cfg.curveExponent()));
    }

    /** Derives the current level for this track fresh from accumulated XP - never stored directly. */
    public int levelOf(PackPlayerProfile profile, MasteryType type) {
        long xp = profile.getMasteryXp().getOrDefault(type.name(), 0L);
        int maxLevel = config.get().maxLevel();
        int level = 0;
        while (level < maxLevel && xp >= xpForLevel(level)) {
            xp -= xpForLevel(level);
            level++;
        }
        return level;
    }

    /** How much XP is banked toward the CURRENT level (i.e. since the last level-up), for a progress display. */
    public long xpIntoCurrentLevel(PackPlayerProfile profile, MasteryType type) {
        long xp = profile.getMasteryXp().getOrDefault(type.name(), 0L);
        int level = levelOf(profile, type);
        for (int i = 0; i < level; i++) {
            xp -= xpForLevel(i);
        }
        return xp;
    }

    /** Adds XP for one track, saves, and flashes a quiet action-bar line if it crossed a level. */
    public void grantXp(Player player, MasteryType type, long amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        int before = levelOf(profile, type);
        profile.getMasteryXp().merge(type.name(), amount, Long::sum);
        store.save(player.getUniqueId());
        int after = levelOf(profile, type);
        if (after > before) {
            Component msg = Text.parse("<#4BD9FF>" + type.name() + " Mastery</#4BD9FF> <gray>is now level <lvl></gray>",
                    Placeholder.unparsed("lvl", String.valueOf(after)));
            player.sendActionBar(msg);
        }
    }

    /** Every perk in {@code type} this player has reached the level for - permanently active once unlocked. */
    public List<MasteryPerk> unlockedPerks(PackPlayerProfile profile, MasteryType type) {
        int level = levelOf(profile, type);
        return config.get().perksFor(type).stream().filter(perk -> perk.level() <= level).toList();
    }

    /** Every configured perk for {@code type}, locked and unlocked alike, sorted by level - for a browsable list (see {@code MasteryGui}). */
    public List<MasteryPerk> allPerks(MasteryType type) {
        return config.get().perksFor(type);
    }

    /**
     * The combined bonus {@code type} currently grants toward {@code stat} -
     * the sum of every unlocked perk's own value for that stat (perks
     * STACK, never "highest tier only" - see {@link MasteryPerk}'s own
     * javadoc). This is the one method every stat-multiplier/bonus provider
     * elsewhere calls; for a multiplicative stat, the CALLER wraps the
     * result as {@code 1.0 + sumStat(...)}, this method itself always
     * returns a plain additive number.
     */
    public double sumStat(PackPlayerProfile profile, MasteryType type, MasteryStat stat) {
        return unlockedPerks(profile, type).stream()
                .filter(perk -> perk.stat() == stat)
                .mapToDouble(MasteryPerk::value)
                .sum();
    }
}
