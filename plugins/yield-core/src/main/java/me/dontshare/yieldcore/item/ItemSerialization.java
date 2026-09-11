package me.dontshare.yieldcore.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Round-trips a real {@link ItemStack} (full ItemMeta, PersistentDataContainer included) through a plain String, safe to store in MongoDB - e.g. yield-auctionhouse listing/claim documents, which need to preserve whatever exact item a player listed, tags and all. */
public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static String serialize(ItemStack item) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize item", e);
        }
    }

    /** Null if {@code data} isn't valid (corrupt, or from an incompatible future/past format). */
    public static ItemStack deserialize(String data) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            return (ItemStack) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}
