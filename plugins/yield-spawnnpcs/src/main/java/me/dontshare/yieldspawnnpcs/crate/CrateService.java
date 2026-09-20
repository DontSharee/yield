package me.dontshare.yieldspawnnpcs.crate;

import me.dontshare.yieldcore.math.WeightedRandom;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Key-based open logic for Crates - the farmed-Key counterpart to {@code
 * yield-lootboxes}' own real-money boxes. Opening spends exactly 1 of that
 * crate's own Key (a virtual count, see {@code PackPlayerProfile
 * #getCrateKeys}); {@link #rollKeyDrops} is the other half, called once per
 * ore cube kill (see {@code CrateKeyDropListener}) to give every crate an
 * independent chance at granting one.
 */
public final class CrateService {

    public enum PurchaseResult {
        SUCCESS, NOT_ENOUGH_KEYS, UNKNOWN_CRATE
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

    /** Spends 1 Key of {@code crateId}'s own type and rolls a reward - called from smacking that crate's physical station (see {@code CrateDisplay}). */
    public PurchaseOutcome open(Player player, String crateId) {
        CrateDefinition crate = content.get().get(crateId);
        if (crate == null) {
            return PurchaseOutcome.of(PurchaseResult.UNKNOWN_CRATE);
        }
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        int keys = profile.getCrateKeys().getOrDefault(crateId, 0);
        if (keys <= 0) {
            return PurchaseOutcome.of(PurchaseResult.NOT_ENOUGH_KEYS);
        }
        profile.getCrateKeys().put(crateId, keys - 1);

        CrateRewardEntry reward = rollOne(crate);
        applyReward(player, profile, reward);
        packs.getPlayerStore().save(player.getUniqueId());
        return new PurchaseOutcome(PurchaseResult.SUCCESS, reward);
    }

    /** One independent roll per crate tier against its own {@link CrateDefinition#keyDropChance} - called once per ore cube kill (see {@code CrateKeyDropListener}), so a single kill can drop keys for more than one crate at once. */
    public void rollKeyDrops(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        boolean any = false;
        for (CrateDefinition crate : content.get().values()) {
            if (crate.keyDropChance() <= 0 || ThreadLocalRandom.current().nextDouble() >= crate.keyDropChance()) {
                continue;
            }
            profile.getCrateKeys().merge(crate.id(), 1, Integer::sum);
            any = true;
            player.sendMessage(Text.parse("<yellow>You found a <crate> Key!</yellow>",
                    Placeholder.unparsed("crate", crate.displayName())));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
        }
        if (any) {
            packs.getPlayerStore().save(player.getUniqueId());
        }
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
        // A pet is a pet however it was obtained - the Shiny roll is flat
        // and source-independent by design (see PackRollService's own
        // maybeRollShiny), so one granted here is exactly as able to come
        // out Shiny as one pulled from a pack.
        packs.getPackRollService().maybeRollShiny(pet);
        if (packs.getEquipmentService().autoEquipOnRoll(profile, pet)) {
            Bukkit.getPluginManager().callEvent(new PetEquippedEvent(player, pet.getInstanceId()));
        }
        packs.getPetDisplayService().refresh(player);
    }
}
