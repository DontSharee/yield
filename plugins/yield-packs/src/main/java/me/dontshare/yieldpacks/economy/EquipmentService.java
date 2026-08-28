package me.dontshare.yieldpacks.economy;

import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Enforces the equip cap and picks which pets are actively earning $/sec.
 * Equipped pets are individual instances, not stacks - equipping the same
 * pet type twice takes two separate slots rather than merging into one, and
 * each equip consumes exactly one unit from the owned count (so owning 22
 * of a type and equipping one leaves 21 in storage, not 0). Auto-equip
 * (called after every roll) silently swaps in a strictly-better pet once at
 * cap; manual equip/unequip (called from the Bag GUI) never auto-evicts
 * anything the player didn't explicitly choose.
 */
public final class EquipmentService {

    private final Supplier<ItemRegistry> itemRegistry;
    private final int equipCap;

    public EquipmentService(Supplier<ItemRegistry> itemRegistry, int equipCap) {
        this.itemRegistry = itemRegistry;
        this.equipCap = equipCap;
    }

    public int getEquipCap() {
        return equipCap;
    }

    /** Called after every single roll (the roll itself is already a fresh, as-yet-unequipped instance). */
    public void autoEquipOnRoll(PackPlayerProfile profile, String rolledItemId) {
        List<String> equipped = profile.getEquippedItemIds();
        if (equipped.size() < equipCap) {
            equipped.add(rolledItemId);
            return;
        }
        double rolledValue = valueOf(rolledItemId);
        Optional<String> weakest = equipped.stream().min(Comparator.comparingDouble(this::valueOf));
        weakest.ifPresent(weakestId -> {
            if (rolledValue > valueOf(weakestId)) {
                equipped.remove(weakestId);
                equipped.add(rolledItemId);
            }
        });
    }

    /**
     * Explicit player action from the Bag GUI - equips exactly one instance
     * out of storage. {@code ownedCount} is the type's total owned count;
     * returns false (no-op) if already at the equip cap, or if every owned
     * copy of this type is already equipped (nothing left in storage).
     */
    public boolean manualEquip(PackPlayerProfile profile, String itemId, int ownedCount) {
        List<String> equipped = profile.getEquippedItemIds();
        if (equipped.size() >= equipCap) {
            return false;
        }
        long alreadyEquipped = equipped.stream().filter(itemId::equals).count();
        if (alreadyEquipped >= ownedCount) {
            return false;
        }
        equipped.add(itemId);
        return true;
    }

    /** Unequips exactly one instance of this type, returning it to storage - other equipped copies of the same type are untouched. */
    public void manualUnequip(PackPlayerProfile profile, String itemId) {
        profile.getEquippedItemIds().remove(itemId);
    }

    private double valueOf(String itemId) {
        return itemRegistry.get().find(itemId).map(ItemDefinition::valuePerSecond).orElse(0.0);
    }
}
