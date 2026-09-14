package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.CommonLadder;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The Enchanting Table's own logic - rolling, applying (always REPLACING a
 * pet's previous enchant state, never accumulating across separate rolls -
 * see {@link #apply}), the diamond cost, the "which pet is currently loaded
 * into this player's table" selection tracking (with its anti-dupe
 * unequip/re-equip), and the same provider-registry pattern {@code
 * ForgeBoostService} already established for feeding COINS/DIAMONDS/LUCK/
 * ATTACK_SPEED into the player's global multipliers only while the pet
 * holding them is equipped - DAMAGE needs no provider here, {@code
 * EquipmentService#effectiveDamage} already reads a pet's own enchant
 * bonus directly.
 */
public final class PetEnchantService {

    /** One completed roll (1 or 2 total results - see {@link #rollFor}). */
    public record RollResult(List<CommonRoll> commons, List<PetUniqueDefinition> uniques) {
        public boolean isEmpty() {
            return commons.isEmpty() && uniques.isEmpty();
        }
    }

    /** {@code level} is 0-based (0 = level I). */
    public record CommonRoll(PetEnchantType type, int level, double value) {
    }

    /** Tracks a pet a player has pulled into the table for editing - {@code wasEquipped} so completing/backing out restores it exactly. */
    private record Selection(UUID petInstanceId, boolean wasEquipped) {
    }

    private final PlayerDataStore<PackPlayerProfile> store;
    private final EquipmentService equipmentService;
    private final Supplier<PetEnchantContent> content;
    private final Map<UUID, Selection> selectionByPlayer = new ConcurrentHashMap<>();
    /** Auto Enchant's own "stop on any of these" Unique-id picks, per player - session-only, same reasoning as {@link #selectionByPlayer}. */
    private final Map<UUID, Set<String>> autoTargetsByPlayer = new ConcurrentHashMap<>();

    public PetEnchantService(PlayerDataStore<PackPlayerProfile> store, EquipmentService equipmentService,
                              Supplier<PetEnchantContent> content) {
        this.store = store;
        this.equipmentService = equipmentService;
        this.content = content;
    }

    public long diamondCost() {
        return content.get().diamondCost();
    }

    public boolean canAfford(PackPlayerProfile profile) {
        return profile.getDiamonds().compareTo(BigInteger.valueOf(diamondCost())) >= 0;
    }

    /** Deducts the diamond cost - caller must have already checked {@link #canAfford}. */
    public void spend(PackPlayerProfile profile) {
        profile.setDiamonds(profile.getDiamonds().subtract(BigInteger.valueOf(diamondCost())));
    }

    // --- Selection (Decision #6 - anti-dupe unequip/re-equip) ---

    public UUID currentlyLoaded(Player player) {
        Selection selection = selectionByPlayer.get(player.getUniqueId());
        return selection == null ? null : selection.petInstanceId();
    }

    /** Loads this pet into the table, unequipping it first if it was equipped - see {@link Selection#wasEquipped}. */
    public void select(Player player, PackPlayerProfile profile, UUID petInstanceId) {
        clearSelection(player, profile);
        boolean wasEquipped = profile.getEquippedPetIds().contains(petInstanceId);
        if (wasEquipped) {
            equipmentService.manualUnequip(profile, petInstanceId);
        }
        selectionByPlayer.put(player.getUniqueId(), new Selection(petInstanceId, wasEquipped));
    }

    /** Drops whatever's currently loaded, re-equipping it first if it came in equipped - safe to call with nothing loaded. */
    public void clearSelection(Player player, PackPlayerProfile profile) {
        Selection previous = selectionByPlayer.remove(player.getUniqueId());
        if (previous != null && previous.wasEquipped() && profile.findPet(previous.petInstanceId()).isPresent()) {
            equipmentService.manualEquip(profile, previous.petInstanceId());
        }
    }

    // --- Auto Enchant targets ---

    /** Which Unique ids this player currently has picked as "stop when I land any of these" - empty if none picked yet. Read-only view. */
    public Set<String> autoTargets(Player player) {
        return Collections.unmodifiableSet(autoTargetsByPlayer.getOrDefault(player.getUniqueId(), Set.of()));
    }

    /** Adds/removes one Unique id from this player's Auto Enchant target set. */
    public void toggleAutoTarget(Player player, String uniqueId) {
        Set<String> targets = autoTargetsByPlayer.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashSet<>());
        if (!targets.remove(uniqueId)) {
            targets.add(uniqueId);
        }
    }

    /** Whether {@code result} landed at least one of {@code targets} - Auto Enchant's own stop condition. */
    public boolean rollMatchesAny(RollResult result, Set<String> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        for (PetUniqueDefinition unique : result.uniques()) {
            if (targets.contains(unique.id())) {
                return true;
            }
        }
        return false;
    }

    // --- Rolling (Decision #5) ---

    /** One full roll - always at least 1 result; a Unique landing on the first roll earns exactly one more roll (never chains further). Never mutates the pet itself - see {@link #apply}. */
    public RollResult rollFor(PetInstance pet) {
        List<CommonRoll> commons = new ArrayList<>();
        List<PetUniqueDefinition> uniques = new ArrayList<>();

        Object first = rollOnce();
        addResult(first, commons, uniques);
        if (first instanceof PetUniqueDefinition) {
            Object second = rollOnce();
            addResult(second, commons, uniques);
        }
        return new RollResult(commons, uniques);
    }

    private void addResult(Object rolled, List<CommonRoll> commons, List<PetUniqueDefinition> uniques) {
        if (rolled instanceof CommonRoll commonRoll) {
            commons.add(commonRoll);
        } else if (rolled instanceof PetUniqueDefinition unique) {
            uniques.add(unique);
        }
    }

    /** Weighted pick across every (type, level) Common and every Unique - {@code Object} return is either a {@link CommonRoll} or a {@link PetUniqueDefinition}. Returns null only if pet-enchants.yml is entirely empty. */
    private Object rollOnce() {
        PetEnchantContent c = content.get();
        List<Object> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        for (Map.Entry<PetEnchantType, CommonLadder> entry : c.commons().entrySet()) {
            CommonLadder ladder = entry.getValue();
            for (int level = 0; level < ladder.values().size(); level++) {
                candidates.add(new CommonRoll(entry.getKey(), level, ladder.values().get(level)));
                weights.add(ladder.weights().get(level));
            }
        }
        for (PetUniqueDefinition unique : c.uniques()) {
            candidates.add(unique);
            weights.add(c.uniqueWeight());
        }

        return WeightedRandom.pick(candidates, weights);
    }

    /** Wholesale-replaces {@code pet}'s enchant state with {@code result} - never merges with what was there before (Decision #5/#7). Contributions from a 2-result roll (e.g. a Unique's own stat bonuses + a bonus Common) DO sum together into the same fresh state, though - that's combining two simultaneously-rolled results, not accumulating across separate rolls. Caller saves. */
    public void apply(PetInstance pet, RollResult result) {
        List<String> uniqueIds = new ArrayList<>();
        Map<PetEnchantType, Double> totals = new EnumMap<>(PetEnchantType.class);
        for (CommonRoll roll : result.commons()) {
            totals.merge(roll.type(), roll.value(), Double::sum);
        }
        for (PetUniqueDefinition unique : result.uniques()) {
            uniqueIds.add(unique.id());
            unique.statBonuses().forEach((type, value) -> totals.merge(type, value, Double::sum));
        }

        pet.getEnchantBonuses().clear();
        totals.forEach((type, value) -> pet.getEnchantBonuses().put(type.name(), value));
        pet.getActiveUniqueEnchants().clear();
        pet.getActiveUniqueEnchants().addAll(uniqueIds);
    }

    // --- Global multiplier providers (mirrors ForgeBoostService exactly) ---

    public double coinMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, PetEnchantType.COINS);
    }

    public double diamondMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, PetEnchantType.DIAMONDS);
    }

    public double attackSpeedMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, PetEnchantType.ATTACK_SPEED);
    }

    /** Additive, not a factor - matches LuckService#extraLuckProviders' own convention. */
    public double luckBonus(PackPlayerProfile profile) {
        return equippedSum(profile, PetEnchantType.LUCK);
    }

    private double equippedSum(PackPlayerProfile profile, PetEnchantType type) {
        double sum = 0;
        for (UUID petId : profile.getEquippedPetIds()) {
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet != null) {
                sum += pet.getEnchantBonuses().getOrDefault(type.name(), 0.0);
                // "GEMS" is the pre-rename map key a pet enchanted before the
                // Diamonds rename would still be carrying - only DIAMONDS
                // ever needs this fallback, and a pet only ever has one of
                // the two keys, so this can't double-count.
                if (type == PetEnchantType.DIAMONDS) {
                    sum += pet.getEnchantBonuses().getOrDefault("GEMS", 0.0);
                }
            }
        }
        return sum;
    }

    // --- Glittering hook (Decision #4) ---

    /** Whether any of these pets currently carries the "BONUS_DIAMOND_DROP" Unique (Glittering) - checked by {@code OreCubeService#payOut} right alongside {@code MilestoneEffect.GUARANTEED_DIAMOND_DROP}. */
    public boolean hasBonusDiamondDropEnchant(List<PetInstance> contributors) {
        List<PetUniqueDefinition> uniques = content.get().uniques();
        for (PetInstance pet : contributors) {
            for (String activeId : pet.getActiveUniqueEnchants()) {
                for (PetUniqueDefinition def : uniques) {
                    if (def.id().equals(activeId) && "BONUS_DIAMOND_DROP".equals(def.specialEffect())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
