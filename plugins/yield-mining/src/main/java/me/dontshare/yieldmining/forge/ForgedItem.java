package me.dontshare.yieldmining.forge;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds and identifies a forged "held item" - the /forge output, right-clicked onto a pet to permanently apply its Multi (see ForgeBoostService). */
public final class ForgedItem {

    /** Purely cosmetic grade material, picked by the combined multiplier that went into forging it - bigger combines feel more substantial to hold. */
    private static final Map<Double, Material> GRADE_THRESHOLDS = Map.of(
            0.0, Material.LEATHER,
            0.01, Material.IRON_INGOT,
            0.05, Material.GOLD_INGOT,
            0.15, Material.DIAMOND,
            0.4, Material.NETHER_STAR
    );

    private final NamespacedKey statKey;
    private final NamespacedKey multiplierKey;

    public ForgedItem(JavaPlugin plugin) {
        this.statKey = new NamespacedKey(plugin, "forged_item_stat");
        this.multiplierKey = new NamespacedKey(plugin, "forged_item_multiplier");
    }

    public ItemStack create(ForgeStatType type, double multiplier) {
        Material grade = gradeFor(multiplier);
        String statLabel = prettyStat(type);
        ItemBuilder builder = ItemBuilder.of(grade)
                .name(MenuLore.infoName("<gold>", grade.name().replace('_', ' ')) + " &8[Held Item]")
                .tag(statKey, PersistentDataType.STRING, type.name())
                .tag(multiplierKey, PersistentDataType.DOUBLE, multiplier)
                .hideAttributes();
        MenuLore.info(
                "held item",
                List.of(" &7Right-click a pet with this", " &7to boost its stats."),
                "<gold>",
                List.of("Multi: &a" + String.format(Locale.ROOT, "%.3f", multiplier) + "x &7(" + statLabel + ")")
        ).forEach(builder::lore);
        return builder.build();
    }

    public ForgeStatType statOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(statKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return ForgeStatType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public double multiplierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Double value = item.getItemMeta().getPersistentDataContainer().get(multiplierKey, PersistentDataType.DOUBLE);
        return value == null ? 0 : value;
    }

    private Material gradeFor(double multiplier) {
        Material grade = Material.LEATHER;
        double best = -1;
        for (Map.Entry<Double, Material> entry : GRADE_THRESHOLDS.entrySet()) {
            if (multiplier >= entry.getKey() && entry.getKey() > best) {
                best = entry.getKey();
                grade = entry.getValue();
            }
        }
        return grade;
    }

    private String prettyStat(ForgeStatType type) {
        return switch (type) {
            case DAMAGE -> "Damage";
            case COINS -> "Money";
            case DIAMONDS -> "Diamonds";
            case LUCK -> "Luck";
            case ATTACK_SPEED -> "Attack Speed";
        };
    }
}
