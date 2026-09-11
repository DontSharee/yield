package me.dontshare.yieldpacks.starter;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A brand-new player otherwise starts with a completely empty Bag - nothing
 * to equip, nothing to fight with, until they open their first pack. This
 * grants exactly one free, deliberately weak (1 damage, see packs.yml's
 * "starter_pup"/"starter_kitten") pet up front, once per profile, so there's
 * always something to send at a cube on day one. Never rollable from a
 * pack's own pool - see the "starter" tag in {@code PackPlayerProfile#addOwnedItem}.
 */
public final class StarterPetService {

    private static final String[] STARTER_ITEM_IDS = {"starter_pup", "starter_kitten"};

    private final PlayerDataStore<PackPlayerProfile> store;
    private final EquipmentService equipmentService;
    private final PetDisplayService petDisplayService;

    public StarterPetService(PlayerDataStore<PackPlayerProfile> store, EquipmentService equipmentService,
                              PetDisplayService petDisplayService) {
        this.store = store;
        this.equipmentService = equipmentService;
        this.petDisplayService = petDisplayService;
    }

    public boolean needsStarterPet(Player player) {
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        return profile != null && !profile.isStarterPetGranted();
    }

    public void grant(Player player, String itemId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.isStarterPetGranted()) {
            return;
        }
        PetInstance pet = profile.addOwnedItem("starter", itemId);
        equipmentService.autoEquipOnRoll(profile, pet);
        profile.setStarterPetGranted(true);
        store.save(player.getUniqueId());
        petDisplayService.refresh(player);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        player.spawnParticle(Particle.HEART, player.getLocation().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0.02);
        player.sendMessage(Text.parse("<green>Your new companion is ready to fight!</green>"));
    }

    public void grantRandom(Player player) {
        String itemId = STARTER_ITEM_IDS[ThreadLocalRandom.current().nextInt(STARTER_ITEM_IDS.length)];
        grant(player, itemId);
    }
}
