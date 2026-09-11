package me.dontshare.yieldpacks.economy;

import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Enforces the equip cap and picks which pets are worth keeping equipped -
 * "worth" meaning effective damage against ore cubes, not idle income.
 * Equipped pets are individual, permanent instances (see {@link PetInstance}) -
 * equipping the same pet type twice takes two separate slots rather than
 * merging into one, and each equip/unequip only ever moves one instance
 * between {@code equippedPetIds} and the rest of {@code pets}, never
 * touching its level/XP. Auto-equip (called after every roll) silently
 * swaps in a strictly-better pet once at cap; manual equip/unequip (called
 * from the Bag GUI) never auto-evicts anything the player didn't explicitly
 * choose.
 */
public final class EquipmentService {

    private final Supplier<ItemRegistry> itemRegistry;
    private final int baseEquipCap;

    /** Extra equip slots from outside sources (e.g. yield-skilltree's EQUIP_SLOTS nodes, yield-ranks' donor ranks) - summed on top of the base cap. Keyed so more than one plugin can contribute at once, matching YieldPacks' own coin/gem/luck provider pattern. */
    private final Map<String, Function<PackPlayerProfile, Integer>> bonusEquipSlotProviders = new ConcurrentHashMap<>();
    /** How much a pet's own level scales its base damage - wired to PetLevelingService once it exists; defaults to no scaling. */
    private volatile Function<PetInstance, Double> levelMultiplierProvider = pet -> 1.0;

    public EquipmentService(Supplier<ItemRegistry> itemRegistry, int baseEquipCap) {
        this.itemRegistry = itemRegistry;
        this.baseEquipCap = baseEquipCap;
    }

    /** Registers (or replaces) this plugin's own keyed bonus-equip-slot contribution - e.g. {@code registerBonusEquipSlotsProvider("skilltree", ...)}. */
    public void registerBonusEquipSlotsProvider(String key, Function<PackPlayerProfile, Integer> provider) {
        bonusEquipSlotProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterBonusEquipSlotsProvider(String key) {
        bonusEquipSlotProviders.remove(key);
    }

    public void setLevelMultiplierProvider(Function<PetInstance, Double> provider) {
        this.levelMultiplierProvider = provider != null ? provider : (pet -> 1.0);
    }

    /** The base cap plus the sum of every currently-registered bonus-slot provider. */
    public int getEquipCap(PackPlayerProfile profile) {
        int bonus = 0;
        for (Function<PackPlayerProfile, Integer> provider : bonusEquipSlotProviders.values()) {
            bonus += provider.apply(profile);
        }
        return baseEquipCap + bonus;
    }

    /** Called after every single roll (the roll itself is already a fresh, as-yet-unequipped instance). Returns whether {@code rolled} actually ended up equipped - lets the caller fire {@code PetEquippedEvent} only when something real happened, e.g. for the tutorial's EQUIP_PET step to count an auto-equip the same as a manual one. */
    public boolean autoEquipOnRoll(PackPlayerProfile profile, PetInstance rolled) {
        List<UUID> equipped = profile.getEquippedPetIds();
        if (equipped.size() < getEquipCap(profile)) {
            equipped.add(rolled.getInstanceId());
            return true;
        }
        double rolledValue = effectiveDamage(profile, rolled);
        Optional<UUID> weakestId = equipped.stream()
                .min(Comparator.comparingDouble(id -> effectiveDamage(profile, profile.findPet(id).orElse(null))));
        if (weakestId.isPresent() && rolledValue > effectiveDamage(profile, profile.findPet(weakestId.get()).orElse(null))) {
            equipped.remove(weakestId.get());
            equipped.add(rolled.getInstanceId());
            return true;
        }
        return false;
    }

    /**
     * Explicit player action from the Bag GUI - equips exactly this one
     * instance out of storage. Returns false (no-op) if it doesn't exist,
     * is already equipped, or the player is already at their equip cap.
     */
    public boolean manualEquip(PackPlayerProfile profile, UUID instanceId) {
        List<UUID> equipped = profile.getEquippedPetIds();
        if (equipped.size() >= getEquipCap(profile) || equipped.contains(instanceId)) {
            return false;
        }
        if (profile.findPet(instanceId).isEmpty()) {
            return false;
        }
        equipped.add(instanceId);
        return true;
    }

    /** Unequips exactly this instance, returning it to storage - its level/XP is never touched. */
    public void manualUnequip(PackPlayerProfile profile, UUID instanceId) {
        profile.getEquippedPetIds().remove(instanceId);
    }

    /**
     * Explicit player action from the Bag GUI's "Equip Best" button - clears
     * whatever's currently equipped and refills the equip cap with the
     * player's own strongest owned pets by {@link #effectiveDamage}, highest
     * first. A full re-pick rather than a top-up, so it's never dependent on
     * what happened to already be equipped going in.
     */
    public void equipBest(PackPlayerProfile profile) {
        List<UUID> best = profile.getPets().stream()
                .sorted(Comparator.comparingDouble((PetInstance pet) -> effectiveDamage(profile, pet)).reversed())
                .limit(getEquipCap(profile))
                .map(PetInstance::getInstanceId)
                .toList();
        profile.getEquippedPetIds().clear();
        profile.getEquippedPetIds().addAll(best);
    }

    /**
     * This pet's damage. For a normal pet: base damage (from its {@link
     * ItemDefinition}) scaled by its own level - see {@link
     * #setLevelMultiplierProvider}. For a Huge pet ({@link ItemDefinition#huge()}):
     * NOT its own stat at all - it's the player's best owned, non-Huge pet's
     * own {@code effectiveDamage} times {@code (1 + hugeDamagePercent)}, so a
     * 100% Huge pet does double whatever your best normal pet does. Computed
     * from every pet the player has ever owned ({@code profile.getPets()}),
     * not just currently-equipped ones - a player can equip an all-Huge team
     * with zero normal pets equipped, and a Huge pet's damage must still be
     * well-defined in that case. Zero for a null/unresolvable pet. Also
     * includes this specific pet's own permanent "DAMAGE" forge bonus (see
     * {@code PetInstance#getForgeBonuses} / yield-mining's ForgeBoostService)
     * AND its own "DAMAGE" enchant bonus (see {@code PetInstance
     * #getEnchantBonuses} / {@code me.dontshare.yieldpacks.petenchant
     * .PetEnchantService}) - two independent, both-apply factors, neither
     * replaces the other. A Huge pet's own bonuses are its OWN, on top of
     * whatever its damage-basis pet contributes, not the basis pet's.
     */
    public double effectiveDamage(PackPlayerProfile profile, PetInstance pet) {
        if (pet == null) {
            return 0.0;
        }
        ItemDefinition def = itemRegistry.get().find(pet.getItemId()).orElse(null);
        if (def == null) {
            return 0.0;
        }
        double forgeBonus = 1.0 + pet.getForgeBonuses().getOrDefault("DAMAGE", 0.0);
        double enchantBonus = 1.0 + pet.getEnchantBonuses().getOrDefault("DAMAGE", 0.0);
        if (def.huge()) {
            return bestNormalPetDamage(profile) * (1.0 + def.hugeDamagePercent()) * forgeBonus * enchantBonus;
        }
        return def.damage() * levelMultiplierProvider.apply(pet) * forgeBonus * enchantBonus;
    }

    /** The best {@code effectiveDamage} among every pet this player has ever owned that ISN'T itself Huge - the basis every equipped Huge pet derives its own damage from. Recursion is bounded to depth 1: this only ever calls {@link #effectiveDamage} on pets already filtered to non-Huge. */
    private double bestNormalPetDamage(PackPlayerProfile profile) {
        return profile.getPets().stream()
                .filter(pet -> !isHuge(pet))
                .mapToDouble(pet -> effectiveDamage(profile, pet))
                .max().orElse(0.0);
    }

    private boolean isHuge(PetInstance pet) {
        return itemRegistry.get().find(pet.getItemId()).map(ItemDefinition::huge).orElse(false);
    }
}
