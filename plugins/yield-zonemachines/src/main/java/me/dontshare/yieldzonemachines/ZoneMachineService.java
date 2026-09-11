package me.dontshare.yieldzonemachines;

import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldrebirth.RebirthService;
import me.dontshare.yieldzonemachines.data.ZoneMachine;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.zone.ZoneLockService;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The purchase/action flow for a physical zone machine - mirrors
 * yield-packstations' own {@code PackStationService} and yield-upgrades'
 * {@code UpgradeService}, except each {@link me.dontshare.yieldzonemachines.data.ZoneMachineType}
 * does something structurally different rather than one shared "buy a pack"
 * shape, so {@link #attemptUse} switches on it explicitly. CANDY_APPLY's
 * actual feeding no longer happens here - smacking it opens {@code
 * CandyApplyGui} instead (see {@code ZoneMachineDisplay#handleClick}); this
 * only gates whether that machine is worth opening at all (do you have a
 * pet equipped to feed).
 */
public final class ZoneMachineService {

    /** The display layer turns each of these into a distinct sound/message rather than one generic "can't do that." */
    public enum Result {
        SUCCESS, ZONE_LOCKED, CANT_AFFORD, NO_EQUIPPED_PETS
    }

    /** {@code detail} carries the SUCCESS-only extra context a chat message needs (e.g. "x3" rebirths) - null for every other result. */
    public record UseResult(Result result, String detail) {
        static UseResult of(Result result) {
            return new UseResult(result, null);
        }

        static UseResult success(String detail) {
            return new UseResult(Result.SUCCESS, detail);
        }
    }

    private final YieldPacks packs;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService zoneLockService;
    private final RebirthService rebirthService;

    public ZoneMachineService(YieldPacks packs, Supplier<Map<String, ZoneDefinition>> zones,
                               ZoneLockService zoneLockService, RebirthService rebirthService) {
        this.packs = packs;
        this.zones = zones;
        this.zoneLockService = zoneLockService;
        this.rebirthService = rebirthService;
    }

    public boolean canUse(Player player, ZoneMachine machine) {
        if (!zoneUnlocked(player, machine)) {
            return false;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        return switch (machine.type()) {
            case REBIRTH -> rebirthService.preview(profile).available() > 0;
            case CANDY_APPLY -> !profile.getEquippedPetIds().isEmpty();
        };
    }

    public UseResult attemptUse(Player player, ZoneMachine machine) {
        if (!zoneUnlocked(player, machine)) {
            return UseResult.of(Result.ZONE_LOCKED);
        }
        return switch (machine.type()) {
            case REBIRTH -> attemptRebirth(player);
            case CANDY_APPLY -> attemptOpenCandyApply(player);
        };
    }

    /** Rebirths as many times as the player can currently afford in one smack - same bulk behavior {@code /rebirth} already has, just triggered physically. */
    private UseResult attemptRebirth(Player player) {
        RebirthService.RebirthPreview preview = rebirthService.rebirth(player);
        if (preview.available() <= 0) {
            return UseResult.of(Result.CANT_AFFORD);
        }
        return UseResult.success(String.valueOf(preview.available()));
    }

    /** SUCCESS here just means "go ahead and open the menu" - {@code ZoneMachineDisplay} does that itself, not this. */
    private UseResult attemptOpenCandyApply(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        if (profile.getEquippedPetIds().isEmpty()) {
            return UseResult.of(Result.NO_EQUIPPED_PETS);
        }
        return UseResult.success(null);
    }

    /** Whichever currently-equipped pet has the highest effective damage right now - same "strongest first" preference {@code PetCombatController#assignNextPetTo} already uses for single-send, so a fed candy always targets the pet a player would expect. Used by {@code CandyApplyGui}. */
    public UUID strongestEquippedPet(PackPlayerProfile profile) {
        UUID best = null;
        double bestDamage = -1;
        for (UUID petId : profile.getEquippedPetIds()) {
            Optional<PetInstance> pet = profile.findPet(petId);
            if (pet.isEmpty()) {
                continue;
            }
            double damage = packs.getEquipmentService().effectiveDamage(profile, pet.get());
            if (damage > bestDamage) {
                bestDamage = damage;
                best = petId;
            }
        }
        return best;
    }

    private boolean zoneUnlocked(Player player, ZoneMachine machine) {
        ZoneDefinition zone = zones.get().get(machine.zoneId());
        return zone != null && zoneLockService.isUnlocked(player, zone);
    }
}
