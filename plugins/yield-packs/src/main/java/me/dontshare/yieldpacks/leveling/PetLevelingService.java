package me.dontshare.yieldpacks.leveling;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.event.PetCandyFedEvent;
import me.dontshare.yieldpacks.event.PetLeveledUpEvent;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * XP/leveling for individual pet instances. A {@link PetInstance} only ever
 * stores progress (level/xp/bonusLevelCap) - every derived number (XP
 * needed for the next level, effective damage, whether a milestone is
 * active) is computed fresh from the CURRENT {@link PetLevelingConfig}
 * every time it's needed, never cached on the instance. That's what makes a
 * mid-season rebalance (editing pet-leveling.yml, then /admin packs reload)
 * apply instantly to every already-leveled pet - only how far a pet has
 * progressed is permanent, never how strong a given level is.
 */
public final class PetLevelingService {

    private final Supplier<PetLevelingConfig> config;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<Map<String, Candy>> candy;
    private final CandyItem candyItem;

    public PetLevelingService(Supplier<PetLevelingConfig> config, PlayerDataStore<PackPlayerProfile> store,
                               Supplier<Map<String, Candy>> candy, CandyItem candyItem) {
        this.config = config;
        this.store = store;
        this.candy = candy;
        this.candyItem = candyItem;
    }

    /** How much XP {@code level} needs to reach {@code level + 1}. */
    public long xpForLevel(int level) {
        PetLevelingConfig cfg = config.get();
        return Math.round(cfg.curveBase() * Math.pow(level, cfg.curveExponent()));
    }

    /** The damage multiplier a pet's own level contributes - consumed by EquipmentService's levelMultiplierProvider hook. */
    public double levelMultiplier(PetInstance pet) {
        return 1.0 + config.get().damagePerLevel() * Math.max(0, pet.getLevel() - 1);
    }

    /** The highest level this pet can currently reach - its base cap plus whatever candy has raised, clamped to the server-wide max. */
    public int cappedMaxLevel(PetInstance pet) {
        PetLevelingConfig cfg = config.get();
        return Math.min(cfg.maxLevel(), cfg.defaultLevelCap() + pet.getBonusLevelCap());
    }

    private static final int XP_BAR_SEGMENTS = 10;

    /** A 10-segment bar + percent toward this pet's next level - "&6&lMAX" once it's hit its current cap (raised by candy). Shared by every screen/item that shows a pet's XP progress (Bag icon, withdrawn physical item, etc.) so they never drift out of sync with each other. */
    public String buildXpLine(PetInstance pet) {
        int cap = cappedMaxLevel(pet);
        if (pet.getLevel() >= cap) {
            return "&7XP: &6&lMAX";
        }
        long needed = xpForLevel(pet.getLevel());
        double ratio = needed <= 0 ? 0 : Math.max(0, Math.min(1.0, pet.getXp() / (double) needed));
        int filled = (int) Math.round(ratio * XP_BAR_SEGMENTS);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < XP_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? "<#55FF7F><st> </st></#55FF7F>" : "<gray><st> </st></gray>");
        }
        return bar + " &7" + Math.round(ratio * 100) + "%";
    }

    /**
     * Grants {@code xpAmount} to every contributing pet on a cube kill
     * (flat per contributor, not split by damage share - "killed a cube"
     * is the credit, not "how much of it you personally chipped in"),
     * rolling level-ups as far as each one's XP and level cap allow, then
     * saves once. A no-op for any instance id that's since been fused/
     * deleted (defensive - a kill can complete on the same tick a pet is
     * removed via a different action).
     */
    public void grantKillXp(Player player, Set<UUID> contributingInstanceIds, long xpAmount) {
        if (contributingInstanceIds.isEmpty() || xpAmount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        boolean changed = false;
        for (UUID instanceId : contributingInstanceIds) {
            PetInstance pet = profile.findPet(instanceId).orElse(null);
            if (pet == null) {
                continue;
            }
            pet.setXp(pet.getXp() + xpAmount);
            int cap = cappedMaxLevel(pet);
            while (pet.getLevel() < cap && pet.getXp() >= xpForLevel(pet.getLevel())) {
                pet.setXp(pet.getXp() - xpForLevel(pet.getLevel()));
                pet.setLevel(pet.getLevel() + 1);
                // Fired per level, not once per grantKillXp call - a single
                // big XP grant can roll several levels at once, and a quest
                // tracking a specific level threshold needs to see it
                // crossed even if the pet jumped straight past it.
                Bukkit.getPluginManager().callEvent(new PetLeveledUpEvent(player, instanceId, pet.getLevel()));
            }
            if (pet.getLevel() >= cap) {
                pet.setXp(Math.min(pet.getXp(), xpForLevel(cap)));
            }
            changed = true;
        }
        if (changed) {
            store.save(player.getUniqueId());
        }
    }

    /** Whether this pet's level has reached the configured threshold for {@code effect} - milestones are cumulative, so this is true for every effect at or below the pet's current level. */
    public boolean hasMilestone(PetInstance pet, MilestoneEffect effect) {
        for (PetMilestone milestone : config.get().milestones()) {
            if (milestone.effect() == effect && pet.getLevel() >= milestone.level()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The highest single EARNINGS_BONUS fraction among {@code contributors}
     * currently active (0 if none) - deliberately the max, not a sum, so
     * several bonus-carrying pets on one kill can't compound into an
     * exploitable multiplier.
     */
    public double earningsBonusFor(Collection<PetInstance> contributors) {
        double best = 0;
        for (PetMilestone milestone : config.get().milestones()) {
            if (milestone.effect() != MilestoneEffect.EARNINGS_BONUS) {
                continue;
            }
            for (PetInstance pet : contributors) {
                if (pet.getLevel() >= milestone.level()) {
                    best = Math.max(best, milestone.value());
                }
            }
        }
        return best;
    }

    /** Which candy (if any) {@code item} is - null/empty for anything else, including a plain material that happens to match one's icon. */
    public Optional<Candy> candyFor(ItemStack item) {
        String id = candyItem.idFor(item);
        return id == null ? Optional.empty() : Optional.ofNullable(candy.get().get(id));
    }

    public ItemStack createCandyItem(Candy c) {
        return candyItem.create(c);
    }

    /**
     * Rolls every configured candy's own drop chance independently
     * (luck-modified, same formula diamond drops already use) - usually
     * returns nothing; can return more than one candy type on a single
     * lucky kill, since each is an independent roll.
     */
    public List<Candy> rollCandyDrops(double luckMultiplier) {
        List<Candy> drops = new ArrayList<>();
        for (Candy c : candy.get().values()) {
            if (ThreadLocalRandom.current().nextDouble() < c.dropChance() * luckMultiplier) {
                drops.add(c);
            }
        }
        return drops;
    }

    /** Raises {@code petInstanceId}'s level cap by {@code c}'s bonus, capped so it can never exceed the server-wide max level - returns false if that instance no longer exists (fused/deleted). */
    public boolean feedCandy(Player player, UUID petInstanceId, Candy c) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        PetInstance pet = profile.findPet(petInstanceId).orElse(null);
        if (pet == null) {
            return false;
        }
        applyCandyDirect(pet, c);
        store.save(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new PetCandyFedEvent(player, petInstanceId, c));
        return true;
    }

    /** Mutates {@code pet}'s own bonusLevelCap directly, with no profile lookup/save - usable for an equipped/bagged pet already inside a profile OR a standalone {@link PetInstance} reconstructed from a withdrawn item (see {@code PetWithdrawItem}), which isn't a member of any profile's pet list at all. Callers own persisting the result. */
    public void applyCandyDirect(PetInstance pet, Candy c) {
        int maxBonus = Math.max(0, config.get().maxLevel() - config.get().defaultLevelCap());
        pet.setBonusLevelCap(Math.min(maxBonus, pet.getBonusLevelCap() + c.levelCapBonus()));
    }
}
