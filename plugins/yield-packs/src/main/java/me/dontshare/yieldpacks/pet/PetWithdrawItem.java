package me.dontshare.yieldpacks.pet;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.leveling.PetLevelingService;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import me.dontshare.yieldpacks.petenchant.PetEnchantLore;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A pet, withdrawn from the Bag into a real, physical item - tagged with
 * everything needed to fully reconstruct the exact same {@link PetInstance}
 * (same id, same level/XP/bonus cap - nothing resets) on redemption, same
 * "tag with an id, no pre-registration needed" idiom as yield-achievements'
 * {@code PotionItem}/yield-lootboxes' {@code LootboxItem}. Sneak+right-click
 * redeems it (see {@code PetRedeemListener}) - deliberately NOT a plain
 * right-click, since {@code GameplayRestrictionsListener} (yield-core)
 * blanket-blocks plain right-click-to-wear for any head-slot-equipment item,
 * which most pets render as.
 */
public final class PetWithdrawItem {

    private final NamespacedKey instanceIdKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey xpKey;
    private final NamespacedKey bonusLevelCapKey;
    private final NamespacedKey forgeBonusesKey;
    private final NamespacedKey enchantBonusesKey;
    private final NamespacedKey uniqueEnchantsKey;
    private final ItemIconFactory iconFactory;
    private final PetLevelingService petLevelingService;
    private final Supplier<PetEnchantContent> petEnchantContent;

    public PetWithdrawItem(JavaPlugin plugin, ItemIconFactory iconFactory, PetLevelingService petLevelingService,
                            Supplier<PetEnchantContent> petEnchantContent) {
        this.instanceIdKey = new NamespacedKey(plugin, "pet_instance_id");
        this.itemIdKey = new NamespacedKey(plugin, "pet_item_id");
        this.levelKey = new NamespacedKey(plugin, "pet_level");
        this.xpKey = new NamespacedKey(plugin, "pet_xp");
        this.bonusLevelCapKey = new NamespacedKey(plugin, "pet_bonus_level_cap");
        // "TYPE:amount;TYPE:amount" - see yield-mining's ForgeStatType/PetInstance#getForgeBonuses.
        // A withdrawn pet leaves the profile's own Mongo-backed PetInstance
        // entirely, so anything on it that isn't one of the fixed
        // tags above (a forge bonus) has to round-trip through the item's
        // own PDC instead, or feeding/forging it while withdrawn would be
        // silently lost the moment it's redeemed back.
        this.forgeBonusesKey = new NamespacedKey(plugin, "pet_forge_bonuses");
        // Same "TYPE:amount;TYPE:amount" shape, same reasoning, for the
        // Enchanting Table's own per-pet bonuses (see PetInstance
        // #getEnchantBonuses) - "id,id" (comma-joined) for the parallel
        // Unique-enchant id list (see PetInstance#getActiveUniqueEnchants).
        this.enchantBonusesKey = new NamespacedKey(plugin, "pet_enchant_bonuses");
        this.uniqueEnchantsKey = new NamespacedKey(plugin, "pet_unique_enchants");
        this.iconFactory = iconFactory;
        this.petLevelingService = petLevelingService;
        this.petEnchantContent = petEnchantContent;
    }

    /** Same Rarity/Damage/Level/XP lore as the Bag icon (see BagGui#buildIcon) - {@code rarity}/{@code effectiveDamage} are passed in rather than recomputed here since the caller (BagGui) already has them on hand from the same BagEntry it just built the icon from. */
    public ItemStack create(ItemDefinition item, PetInstance pet, Rarity rarity, double effectiveDamage) {
        String accent = rarity != null ? "<" + rarity.colorHex() + ">" : "<#FFFFFF>";
        List<String> data = new ArrayList<>();
        data.add("&7Rarity: " + (rarity != null ? rarity.displayName() : "&7Unknown"));
        if (item.huge()) {
            data.add("&7Damage: &4❤&c" + Formatting.format(effectiveDamage)
                    + " &7(" + Formatting.format(item.hugeDamagePercent() * 100) + "% of best pet)");
        } else {
            data.add("&7Damage: &4❤&c" + Formatting.format(effectiveDamage));
        }
        data.add("&7Level: &f" + pet.getLevel() + " &8(" + petLevelingService.cappedMaxLevel(pet) + ")");
        data.add(petLevelingService.buildXpLine(pet));
        for (Map.Entry<String, Double> entry : pet.getForgeBonuses().entrySet()) {
            if (entry.getValue() != 0.0) {
                data.add("&7Forged " + prettyStatKey(entry.getKey()) + ": &a+"
                        + String.format(java.util.Locale.ROOT, "%.1f", entry.getValue() * 100) + "%");
            }
        }
        PetEnchantLore.appendEnchantLines(data, pet, petEnchantContent.get());

        var builder = iconFactory.baseIcon(item).name(item.displayName());
        MenuLore.info("pet", List.of(), accent, data).forEach(builder::lore);
        builder.lore("")
                .lore("&7Losing this item loses the pet.")
                .lore("")
                .lore("&8Shift+Right-Click to Redeem")
                .tag(instanceIdKey, PersistentDataType.STRING, pet.getInstanceId().toString())
                .tag(itemIdKey, PersistentDataType.STRING, pet.getItemId())
                .tag(levelKey, PersistentDataType.INTEGER, pet.getLevel())
                .tag(xpKey, PersistentDataType.LONG, pet.getXp())
                .tag(bonusLevelCapKey, PersistentDataType.INTEGER, pet.getBonusLevelCap())
                .hideAttributes();
        if (!pet.getForgeBonuses().isEmpty()) {
            builder.tag(forgeBonusesKey, PersistentDataType.STRING, encodeStatMap(pet.getForgeBonuses()));
        }
        if (!pet.getEnchantBonuses().isEmpty()) {
            builder.tag(enchantBonusesKey, PersistentDataType.STRING, encodeStatMap(pet.getEnchantBonuses()));
        }
        if (!pet.getActiveUniqueEnchants().isEmpty()) {
            builder.tag(uniqueEnchantsKey, PersistentDataType.STRING, String.join(",", pet.getActiveUniqueEnchants()));
        }
        return builder.build();
    }

    /** "ATTACK_SPEED" -> "Attack Speed" - shared by the Forge lore line above (raw {@code ForgeStatType#name()} keys, yield-packs can't reference that enum directly - wrong dependency direction). */
    private String prettyStatKey(String rawKey) {
        String[] words = rawKey.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return result.toString();
    }

    private String encodeStatMap(Map<String, Double> bonuses) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Double> entry : bonuses.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return builder.toString();
    }

    private Map<String, Double> decodeStatMap(String raw) {
        Map<String, Double> bonuses = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return bonuses;
        }
        for (String entry : raw.split(";")) {
            int separator = entry.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            try {
                bonuses.put(entry.substring(0, separator), Double.parseDouble(entry.substring(separator + 1)));
            } catch (NumberFormatException ignored) {
                // Malformed entry - skip it rather than failing the whole read.
            }
        }
        return bonuses;
    }

    /** Reconstructs the exact original {@link PetInstance} (same id/level/xp/bonus cap) from a withdrawn-pet item, or null if it isn't one at all. */
    public PetInstance read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String instanceIdRaw = meta.getPersistentDataContainer().get(instanceIdKey, PersistentDataType.STRING);
        String itemId = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (instanceIdRaw == null || itemId == null) {
            return null;
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(instanceIdRaw);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Integer level = meta.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        Long xp = meta.getPersistentDataContainer().get(xpKey, PersistentDataType.LONG);
        Integer bonusLevelCap = meta.getPersistentDataContainer().get(bonusLevelCapKey, PersistentDataType.INTEGER);

        String forgeBonusesRaw = meta.getPersistentDataContainer().get(forgeBonusesKey, PersistentDataType.STRING);
        String enchantBonusesRaw = meta.getPersistentDataContainer().get(enchantBonusesKey, PersistentDataType.STRING);
        String uniqueEnchantsRaw = meta.getPersistentDataContainer().get(uniqueEnchantsKey, PersistentDataType.STRING);

        PetInstance pet = new PetInstance(instanceId, itemId);
        pet.setLevel(level != null ? level : 1);
        pet.setXp(xp != null ? xp : 0L);
        pet.setBonusLevelCap(bonusLevelCap != null ? bonusLevelCap : 0);
        pet.getForgeBonuses().putAll(decodeStatMap(forgeBonusesRaw));
        pet.getEnchantBonuses().putAll(decodeStatMap(enchantBonusesRaw));
        if (uniqueEnchantsRaw != null && !uniqueEnchantsRaw.isBlank()) {
            pet.getActiveUniqueEnchants().addAll(Arrays.asList(uniqueEnchantsRaw.split(",")));
        }
        return pet;
    }

    public boolean isWithdrawnPet(ItemStack item) {
        return read(item) != null;
    }
}
