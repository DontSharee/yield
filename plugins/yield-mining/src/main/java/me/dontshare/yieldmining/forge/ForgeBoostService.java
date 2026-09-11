package me.dontshare.yieldmining.forge;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Applying a forged held item's Multi to one specific pet, and feeding the
 * COINS/GEMS/LUCK/ATTACK_SPEED flavors of that back into the player's
 * global multipliers - only while the pet holding them is actually
 * equipped (see {@link PetInstance#getForgeBonuses}). DAMAGE needs no
 * service-level read here: {@code EquipmentService#effectiveDamage}
 * already reads a pet's own forge bonus directly.
 */
public final class ForgeBoostService {

    private final PlayerDataStore<PackPlayerProfile> store;

    public ForgeBoostService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    /** Applies a forged item's bonus to this one pet, saves, and returns that pet's new total for this stat. */
    public double consume(Player player, PetInstance pet, ForgeStatType type, double amount) {
        double newTotal = pet.getForgeBonuses().getOrDefault(type.name(), 0.0) + amount;
        pet.getForgeBonuses().put(type.name(), newTotal);
        store.save(player.getUniqueId());
        return newTotal;
    }

    public double coinMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, ForgeStatType.COINS);
    }

    public double gemMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, ForgeStatType.GEMS);
    }

    /** Additive, not a factor - matches LuckService#extraLuckProviders' own convention. */
    public double luckBonus(PackPlayerProfile profile) {
        return equippedSum(profile, ForgeStatType.LUCK);
    }

    public double attackSpeedMultiplier(PackPlayerProfile profile) {
        return 1.0 + equippedSum(profile, ForgeStatType.ATTACK_SPEED);
    }

    private double equippedSum(PackPlayerProfile profile, ForgeStatType type) {
        double sum = 0;
        for (UUID petId : profile.getEquippedPetIds()) {
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet != null) {
                sum += pet.getForgeBonuses().getOrDefault(type.name(), 0.0);
            }
        }
        return sum;
    }
}
