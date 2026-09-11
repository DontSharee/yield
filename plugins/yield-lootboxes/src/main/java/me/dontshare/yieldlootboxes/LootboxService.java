package me.dontshare.yieldlootboxes;

import me.dontshare.yieldlootboxes.data.LootboxDefinition;
import me.dontshare.yieldlootboxes.data.LootboxRewardEntry;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Grant + open logic for lootboxes - a reward container entirely separate
 * from Packs (see {@code me.dontshare.yieldpacks.roll.PackRollService}),
 * meant to be sold for real money via a webstore (Tebex/Buycraft) rather
 * than bought with in-game currency. A box is a real, physical item (see
 * {@link LootboxItem}) - "owning" one just means having it in your
 * inventory, right-clicking opens it (see {@link LootboxConsumeListener}).
 * Nothing here ever processes a payment - {@link #give} is the one and only
 * entry point a Tebex package's configured command reaches (see
 * lootboxes.yml's own header comment), called after Tebex's own plugin has
 * already confirmed the purchase.
 */
public final class LootboxService {

    private final Supplier<Map<String, LootboxDefinition>> content;
    private final YieldPacks packs;
    private final LootboxItem lootboxItem;

    public LootboxService(Supplier<Map<String, LootboxDefinition>> content, YieldPacks packs, LootboxItem lootboxItem) {
        this.content = content;
        this.packs = packs;
        this.lootboxItem = lootboxItem;
    }

    public Map<String, LootboxDefinition> content() {
        return content.get();
    }

    /** Hands {@code player} {@code amount} real, giveable lootbox items - the command a Tebex package (or an admin) invokes to actually grant a real-money purchase. Overflow past a full inventory drops at their feet rather than vanishing, same as candy drops. */
    public boolean give(Player player, String boxId, int amount) {
        LootboxDefinition box = content.get().get(boxId);
        if (box == null || amount <= 0) {
            return false;
        }
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(64, remaining);
            ItemStack stack = lootboxItem.create(box, stackSize);
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            remaining -= stackSize;
        }
        return true;
    }

    /** Rolls one box's weighted pool and applies the result - called from {@link LootboxConsumeListener} once the item itself has already been consumed. */
    public void open(Player player, LootboxDefinition box) {
        LootboxRewardEntry rolled = rollOne(box);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        applyReward(player, profile, rolled);
        packs.getPlayerStore().save(player.getUniqueId());
    }

    private LootboxRewardEntry rollOne(LootboxDefinition box) {
        double totalWeight = box.pool().stream().mapToDouble(LootboxRewardEntry::weight).sum();
        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0;
        for (LootboxRewardEntry entry : box.pool()) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        return box.pool().get(box.pool().size() - 1);
    }

    private void applyReward(Player player, PackPlayerProfile profile, LootboxRewardEntry reward) {
        switch (reward.type()) {
            case FLAT_COINS -> profile.setCoins(profile.getCoins().add(BigInteger.valueOf(reward.amount())));
            case FLAT_GEMS -> profile.setGems(profile.getGems().add(BigInteger.valueOf(reward.amount())));
            case FLAT_CREDITS -> profile.setCredits(profile.getCredits().add(BigInteger.valueOf(reward.amount())));
            case PET -> grantPet(player, profile, reward.petItemId());
            case COMMANDS -> reward.commands().forEach(command ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName())));
        }
    }

    private void grantPet(Player player, PackPlayerProfile profile, String itemId) {
        if (itemId == null || packs.getItemRegistry().find(itemId).isEmpty()) {
            return;
        }
        PetInstance pet = profile.addOwnedItem("lootbox", itemId);
        if (packs.getEquipmentService().autoEquipOnRoll(profile, pet)) {
            Bukkit.getPluginManager().callEvent(new PetEquippedEvent(player, pet.getInstanceId()));
        }
        packs.getPetDisplayService().refresh(player);
    }
}
