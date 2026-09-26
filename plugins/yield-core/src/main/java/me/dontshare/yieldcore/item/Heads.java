package me.dontshare.yieldcore.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player heads wearing a custom texture - the base64 "value" string head
 * sites like minecraft-heads.com give for each head. No HeadDatabase
 * plugin needed.
 */
public final class Heads {

    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();

    private Heads() {
    }

    /** A fresh copy of the head with this texture. */
    public static ItemStack texture(String base64) {
        return CACHE.computeIfAbsent(base64, Heads::build).clone();
    }

    private static ItemStack build(String base64) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            // A stable id per texture, so identical heads stack and the
            // client caches the skin once.
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(base64.getBytes(StandardCharsets.UTF_8)));
            profile.setProperty(new ProfileProperty("textures", base64));
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
        }
        return head;
    }
}
