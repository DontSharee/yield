package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;

import java.util.List;
import java.util.Locale;

/**
 * The shared "&#9670; &lt;Stat&gt;: +X%" / "&#9670; &lt;Unique Name&gt;" lore
 * lines for a pet's active Enchanting Table results - a distinct diamond
 * bullet + light-purple label so these read apart from {@code BagGui}'s
 * existing plain-gray "Forged &lt;Stat&gt;: +X%" lines in the same block,
 * even though both ultimately feed the same {@code MenuLore} data-line
 * renderer. Shared by {@code BagGui}, {@code PetEnchantTableGui}, and
 * {@code PetWithdrawItem} so a pet's enchants render identically wherever
 * its icon shows up.
 */
public final class PetEnchantLore {

    private PetEnchantLore() {
    }

    public static void appendEnchantLines(List<String> data, PetInstance pet, PetEnchantContent content) {
        for (PetEnchantType type : PetEnchantType.values()) {
            double bonus = pet.getEnchantBonuses().getOrDefault(type.name(), 0.0);
            if (bonus != 0.0) {
                data.add("&d✦ " + type.displayName() + ": &a+" + String.format(Locale.ROOT, "%.1f", bonus * 100) + "%");
            }
        }
        for (String uniqueId : pet.getActiveUniqueEnchants()) {
            content.uniques().stream()
                    .filter(u -> u.id().equals(uniqueId))
                    .findFirst()
                    .ifPresent(u -> data.add("<" + u.colorHex() + ">✦ " + u.displayName() + "</" + u.colorHex() + ">"));
        }
    }
}
