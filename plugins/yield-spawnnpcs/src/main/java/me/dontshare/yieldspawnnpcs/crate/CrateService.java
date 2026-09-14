package me.dontshare.yieldspawnnpcs.crate;

import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Buy-and-open-instantly logic for Crates - the in-game-Credits counterpart
 * to {@code yield-lootboxes}' own real-money boxes, same afford-check-then-
 * subtract idiom every currency spend in this codebase already uses
 * ({@code ZoneLockService}/{@code RankService}/{@code PickaxeEnchantService}).
 */
public final class CrateService {

    public enum PurchaseResult {
        SUCCESS, CANT_AFFORD, UNKNOWN_CRATE
    }

    public record PurchaseOutcome(PurchaseResult result, CrateRewardEntry reward) {
        static PurchaseOutcome of(PurchaseResult result) {
            return new PurchaseOutcome(result, null);
        }
    }

    private final Supplier<Map<String, CrateDefinition>> content;
    private final YieldPacks packs;

    public CrateService(Supplier<Map<String, CrateDefinition>> content, YieldPacks packs) {
        this.content = content;
        this.packs = packs;
    }

    public Map<String, CrateDefinition> content() {
        return content.get();
    }

    public PurchaseOutcome buy(Player player, String crateId) {
        CrateDefinition crate = content.get().get(crateId);
        if (crate == null) {
            return PurchaseOutcome.of(PurchaseResult.UNKNOWN_CRATE);
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        BigInteger cost = BigInteger.valueOf(crate.cost());
        if (profile.getCredits().compareTo(cost) < 0) {
            return PurchaseOutcome.of(PurchaseResult.CANT_AFFORD);
        }
        profile.setCredits(profile.getCredits().subtract(cost));

        CrateRewardEntry reward = rollOne(crate);
        applyReward(player, profile, reward);
        packs.getPlayerStore().save(player.getUniqueId());
        return new PurchaseOutcome(PurchaseResult.SUCCESS, reward);
    }

    private CrateRewardEntry rollOne(CrateDefinition crate) {
        return WeightedRandom.pick(crate.pool(), CrateRewardEntry::weight);
    }

    private void applyReward(Player player, PackPlayerProfile profile, CrateRewardEntry reward) {
        switch (reward.type()) {
            case FLAT_COINS -> profile.setCoins(profile.getCoins().add(BigInteger.valueOf(reward.amount())));
            case FLAT_DIAMONDS -> profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(reward.amount())));
            case PET -> grantPet(player, profile, reward.petItemId());
            case COMMANDS -> reward.commands().forEach(command ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName())));
        }
    }

    private void grantPet(Player player, PackPlayerProfile profile, String itemId) {
        if (itemId == null || packs.getItemRegistry().find(itemId).isEmpty()) {
            return;
        }
        PetInstance pet = profile.addOwnedItem("crate", itemId);
        if (packs.getEquipmentService().autoEquipOnRoll(profile, pet)) {
            Bukkit.getPluginManager().callEvent(new PetEquippedEvent(player, pet.getInstanceId()));
        }
        packs.getPetDisplayService().refresh(player);
    }
}
