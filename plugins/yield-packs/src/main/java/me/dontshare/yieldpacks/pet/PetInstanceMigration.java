package me.dontshare.yieldpacks.pet;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Two independent, one-time raw-document migrations, both wired in via
 * {@code PlayerDataStore#setRawMigration} (only one migration function can
 * be registered per store, so both live here rather than as two separate
 * classes). Each has its own independent guard/skip check - critical,
 * since a profile can need one without the other (e.g. a profile already
 * migrated to {@link PetInstance}s in an earlier deploy still needs the
 * {@code attackMode} split below applied on its next load).
 *
 * <h2>1. Stack-based pets -&gt; persistent {@link PetInstance}s</h2>
 * From the old {@code ownedItems: Map<String,Integer>} +
 * {@code equippedItemIds: List<String>} shape. Runs on every load, but is a
 * no-op the moment a profile has already been migrated (its saved document
 * carries "pets" from then on, since {@link PlayerDataStore#save} always
 * re-serializes the whole object). Every previously-owned pet becomes a
 * level-1 baseline instance - fair, since leveling didn't exist before
 * this - with exactly as many marked equipped as the old
 * {@code equippedItemIds} list had of that type, so both a player's total
 * collection and their equip loadout are preserved exactly.
 *
 * <h2>2. {@code attackMode}'s meaning split into two fields</h2>
 * {@code attackMode} used to mean "how pets auto-target"
 * (CLOSEST/STRONGEST/WEAKEST/SINGLE). That field is now
 * {@code autoTargetMode} (CLOSEST/STRONGEST/WEAKEST only), and
 * {@code attackMode} was repurposed for a completely different concept
 * (SINGLE/ALL - what a manual click sends). Without this migration, an old
 * saved {@code attackMode: "CLOSEST"} fails to decode into the new
 * enum entirely and **blocks that player from logging in** - this is not
 * a cosmetic fix, it's load-bearing.
 */
public final class PetInstanceMigration {

    private PetInstanceMigration() {
    }

    public static Document migrate(Document doc) {
        migratePets(doc);
        migrateAttackMode(doc);
        return doc;
    }

    private static void migratePets(Document doc) {
        if (doc.containsKey("pets") || !doc.containsKey("ownedItems")) {
            return;
        }

        Document ownedItems = doc.get("ownedItems", Document.class);
        @SuppressWarnings("unchecked")
        List<String> equippedItemIds = (List<String>) doc.get("equippedItemIds", List.class);
        if (equippedItemIds == null) {
            equippedItemIds = List.of();
        }

        List<Document> pets = new ArrayList<>();
        List<UUID> equippedPetIds = new ArrayList<>();
        // How many more instances of this item id still need to be marked
        // equipped, decremented as each one is created below.
        java.util.Map<String, Integer> remainingEquippedByItemId = new java.util.HashMap<>();
        for (String itemId : equippedItemIds) {
            remainingEquippedByItemId.merge(itemId, 1, Integer::sum);
        }

        if (ownedItems != null) {
            for (String itemId : ownedItems.keySet()) {
                int count = ownedItems.getInteger(itemId, 0);
                int stillEquipped = remainingEquippedByItemId.getOrDefault(itemId, 0);
                for (int i = 0; i < count; i++) {
                    UUID instanceId = UUID.randomUUID();
                    Document pet = new Document()
                            .append("instanceId", instanceId)
                            .append("itemId", itemId)
                            .append("level", 1)
                            .append("xp", 0L)
                            .append("bonusLevelCap", 0);
                    pets.add(pet);
                    if (stillEquipped > 0) {
                        equippedPetIds.add(instanceId);
                        stillEquipped--;
                    }
                }
            }
        }

        doc.put("pets", pets);
        doc.put("equippedPetIds", equippedPetIds);
    }

    private static void migrateAttackMode(Document doc) {
        if (doc.containsKey("autoTargetMode") || !doc.containsKey("attackMode")) {
            return;
        }
        String old = String.valueOf(doc.get("attackMode"));
        String autoTargetMode = switch (old) {
            case "CLOSEST", "STRONGEST", "WEAKEST" -> old;
            // Old SINGLE meant "never auto-target" - AutoTargetMode has no
            // equivalent for that anymore (see SendMode instead), so just
            // fall back to its own default.
            default -> "CLOSEST";
        };
        doc.put("autoTargetMode", autoTargetMode);
        // The raw "attackMode" key now must hold a value valid for the NEW
        // AttackMode enum (SINGLE/ALL) - old SINGLE happens to mean the same
        // thing today (manual single-send), anything else defaults to it.
        doc.put("attackMode", "SINGLE");
    }
}
