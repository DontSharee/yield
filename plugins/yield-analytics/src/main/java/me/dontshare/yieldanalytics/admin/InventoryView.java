package me.dontshare.yieldanalytics.admin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's inventory as plain rows for the site - one per filled slot, in
 * the numbering {@link PlayerInventory} uses: 0-8 hotbar, 9-35 the rest,
 * 36-39 boots to helmet, 40 the off hand.
 */
public final class InventoryView {

    private static final Gson GSON = new Gson();

    private InventoryView() {
    }

    /** Main thread. */
    public static List<Map<String, Object>> rows(Player player) {
        PlayerInventory inventory = player.getInventory();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            rows.add(row(slot, item));
        }
        return rows;
    }

    /** Main thread: the same rows as JSON, for storing as they log out. */
    public static String json(Player player) {
        return GSON.toJson(rows(player));
    }

    public static List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = GSON.fromJson(json, new TypeToken<List<Map<String, Object>>>() { }.getType());
            return rows != null ? rows : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Map<String, Object> row(int slot, ItemStack item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slot", slot);
        row.put("material", item.getType().getKey().getKey());
        row.put("amount", item.getAmount());
        ItemMeta meta = item.getItemMeta();
        String name = null;
        List<String> lore = new ArrayList<>();
        boolean glint = false;
        if (meta != null) {
            if (meta.hasDisplayName()) {
                name = plain(meta.displayName());
            } else if (meta.hasItemName()) {
                name = plain(meta.itemName());
            }
            List<Component> lines = meta.lore();
            if (lines != null) {
                for (Component line : lines) {
                    lore.add(plain(line));
                }
            }
            glint = meta.hasEnchants() || (meta.hasEnchantmentGlintOverride() && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride()));
        }
        row.put("name", name != null && !name.isBlank() ? name : pretty(item.getType().getKey().getKey()));
        row.put("lore", lore);
        row.put("glint", glint);
        return row;
    }

    /** Main thread: the exact item, restorable byte for byte. */
    static String serialize(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack deserialize(String base64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** {@code diamond_sword} → {@code Diamond Sword}. */
    static String pretty(String key) {
        StringBuilder out = new StringBuilder();
        for (String word : key.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
