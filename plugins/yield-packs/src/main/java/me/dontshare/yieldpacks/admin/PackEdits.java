package me.dontshare.yieldpacks.admin;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.admin.PlayerEdits;
import me.dontshare.yieldcore.admin.PlayerEdits.Kind;
import me.dontshare.yieldcore.admin.PlayerEdits.Option;
import me.dontshare.yieldcore.admin.PlayerEdits.Stat;
import me.dontshare.yieldcore.admin.PlayerEdits.Undo;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.pet.PetInstance;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What an admin tool may change on a player's yield-packs record: the three
 * currencies, rebirths and prestiges, and their pets - given, removed, or
 * put back after a removal. Plus the "pets" view, their whole collection.
 */
public final class PackEdits {

    private final YieldPacks plugin;
    private final Supplier<RarityRegistry> rarities;

    public PackEdits(YieldPacks plugin, Supplier<RarityRegistry> rarities) {
        this.plugin = plugin;
        this.rarities = rarities;
    }

    public void register() {
        amount("coins", "Coins", PackPlayerProfile::getCoins, PackPlayerProfile::setCoins);
        amount("diamonds", "Diamonds", PackPlayerProfile::getDiamonds, PackPlayerProfile::setDiamonds);
        amount("credits", "Credits", PackPlayerProfile::getCredits, PackPlayerProfile::setCredits);
        number("rebirths", "Rebirths", PackPlayerProfile::getRebirths, PackPlayerProfile::setRebirths);
        number("prestiges", "Prestiges", PackPlayerProfile::getPrestiges, PackPlayerProfile::setPrestiges);

        PlayerEdits.register(new Stat<>("pets.give", "Give a pet", "Pets", Kind.ACTION, 0, 0,
                "The pet goes into their bag, not equipped. Add \":shiny\" for a shiny one.",
                plugin.getPlayerStore(), profile -> String.valueOf(profile.getPets().size()),
                (profile, value) -> {
                    boolean shiny = value.endsWith(":shiny");
                    String itemId = shiny ? value.substring(0, value.length() - ":shiny".length()) : value;
                    if (plugin.getItemRegistry().find(itemId).isEmpty()) {
                        throw new IllegalArgumentException("No such pet: " + itemId);
                    }
                    PetInstance pet = profile.addOwnedItem("admin", itemId);
                    pet.setShiny(shiny);
                    return new Undo("pets.remove", pet.getInstanceId().toString());
                }, this::petOptions, this::refreshPets, plugin));

        PlayerEdits.register(new Stat<>("pets.remove", "Remove a pet", "Pets", Kind.ACTION, 0, 0,
                "Takes the pet out of their bag - unequipping it first if it's out.",
                plugin.getPlayerStore(), profile -> String.valueOf(profile.getPets().size()),
                (profile, value) -> {
                    UUID id;
                    try {
                        id = UUID.fromString(value);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Not a pet id: " + value);
                    }
                    PetInstance pet = profile.findPet(id).orElseThrow(() -> new IllegalArgumentException("They don't have that pet any more."));
                    String saved = encode(pet);
                    profile.getEquippedPetIds().remove(id);
                    profile.removePet(id);
                    return new Undo("pets.restore", saved);
                }, List::of, this::refreshPets, plugin));

        PlayerEdits.register(new Stat<>("pets.restore", "Put back a removed pet", "Pets", Kind.ACTION, 0, 0,
                "Used by undo - the exact pet that was removed, level, enchants and all.",
                plugin.getPlayerStore(), profile -> String.valueOf(profile.getPets().size()),
                (profile, value) -> {
                    PetInstance pet = decode(value);
                    if (profile.findPet(pet.getInstanceId()).isPresent()) {
                        throw new IllegalArgumentException("They already have that pet.");
                    }
                    profile.addPet(pet);
                    return new Undo("pets.remove", pet.getInstanceId().toString());
                }, List::of, this::refreshPets, plugin));

        PlayerEdits.registerView(new PlayerEdits.View<>("pets", plugin.getPlayerStore(), this::petsView, plugin));
    }

    private void amount(String id, String label, Function<PackPlayerProfile, BigInteger> get,
                        BiConsumer<PackPlayerProfile, BigInteger> set) {
        PlayerEdits.register(new Stat<>(id, label, "Currencies", Kind.AMOUNT, 0, 0,
                "Shorthand works: 250k, 1.5m, 3b.", plugin.getPlayerStore(),
                profile -> String.valueOf(get.apply(profile)),
                (profile, value) -> {
                    String before = String.valueOf(get.apply(profile));
                    set.accept(profile, PlayerEdits.amount(value));
                    return PlayerEdits.restore(id, before);
                }, List::of, null, plugin));
    }

    private void number(String id, String label, Function<PackPlayerProfile, Integer> get,
                        BiConsumer<PackPlayerProfile, Integer> set) {
        PlayerEdits.register(new Stat<>(id, label, "Progress", Kind.NUMBER, 0, 1_000_000, null,
                plugin.getPlayerStore(), profile -> String.valueOf(get.apply(profile)),
                (profile, value) -> {
                    String before = String.valueOf(get.apply(profile));
                    set.accept(profile, (int) PlayerEdits.number(value, 0, 1_000_000));
                    return PlayerEdits.restore(id, before);
                }, List::of, null, plugin));
    }

    /** Every pet there is, rarest first - what "Give a pet" offers. */
    private List<Option> petOptions() {
        RarityRegistry byId = rarities.get();
        List<ItemDefinition> items = new ArrayList<>(plugin.getItemRegistry().all());
        items.sort(Comparator.comparingInt((ItemDefinition item) -> sortOrder(byId, item)).reversed()
                .thenComparing(ItemDefinition::id));
        List<Option> options = new ArrayList<>();
        for (ItemDefinition item : items) {
            Rarity rarity = byId.find(item.rarityId()).orElse(null);
            String tier = item.fusionTier() == null || item.fusionTier().name().equals("NORMAL") ? ""
                    : " (" + titleCase(item.fusionTier().name()) + ")";
            options.add(new Option(item.id(), plain(item.displayName()) + tier + (item.huge() ? " (Huge)" : ""),
                    rarity != null ? plain(rarity.displayName()) : item.rarityId()));
        }
        return options;
    }

    private static int sortOrder(RarityRegistry rarities, ItemDefinition item) {
        Rarity rarity = rarities.find(item.rarityId()).orElse(null);
        return rarity != null ? rarity.sortOrder() : 0;
    }

    /** Their whole collection: one entry per pet, equipped ones first, then rarest. */
    private Object petsView(PackPlayerProfile profile) {
        RarityRegistry byId = rarities.get();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> rarityCounts = new HashMap<>();
        for (PetInstance pet : profile.getPets()) {
            ItemDefinition item = plugin.getItemRegistry().find(pet.getItemId()).orElse(null);
            Rarity rarity = item != null ? byId.find(item.rarityId()).orElse(null) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pet.getInstanceId().toString());
            row.put("itemId", pet.getItemId());
            row.put("name", item != null ? plain(item.displayName()) : pet.getItemId());
            row.put("rarity", rarity != null ? plain(rarity.displayName()) : item != null ? item.rarityId() : "unknown");
            row.put("rarityColor", rarity != null ? rarity.colorHex() : "#8b95a7");
            row.put("rarityOrder", rarity != null ? rarity.sortOrder() : 0);
            row.put("material", item != null ? item.material().getKey().getKey() : "barrier");
            row.put("level", pet.getLevel());
            row.put("shiny", pet.isShiny());
            row.put("huge", item != null && item.huge());
            row.put("tier", item != null && item.fusionTier() != null ? item.fusionTier().name().toLowerCase(java.util.Locale.ROOT) : "normal");
            row.put("damage", item != null ? item.damage() : 0);
            row.put("equipped", profile.isEquipped(pet.getInstanceId()));
            row.put("enchants", pet.getActiveUniqueEnchants() == null ? List.of() : List.copyOf(pet.getActiveUniqueEnchants()));
            rows.add(row);
            rarityCounts.merge(String.valueOf(row.get("rarity")), 1, Integer::sum);
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> !(Boolean) row.get("equipped"))
                .thenComparing(row -> -(Integer) row.get("rarityOrder"))
                .thenComparing(row -> String.valueOf(row.get("name"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pets", rows);
        out.put("equippedCount", profile.getEquippedPetIds().size());
        out.put("rarityCounts", rarityCounts);
        return out;
    }

    private void refreshPets(Player player) {
        plugin.getPetDisplayService().refresh(player);
    }

    private Codec<PetInstance> codec() {
        return JavaPlugin.getPlugin(YieldCore.class).getDatabaseManager().getDatabase().getCodecRegistry().get(PetInstance.class);
    }

    private String encode(PetInstance pet) {
        BsonDocument document = new BsonDocument();
        codec().encode(new BsonDocumentWriter(document), pet, EncoderContext.builder().isEncodingCollectibleDocument(true).build());
        return document.toJson();
    }

    private PetInstance decode(String json) {
        try {
            return codec().decode(new BsonDocumentReader(BsonDocument.parse(json)), DecoderContext.builder().build());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("That isn't a saved pet.");
        }
    }

    private static String titleCase(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.toLowerCase(java.util.Locale.ROOT).split("_")) {
            out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("(?i)[&§][0-9a-fk-orx#]", "").replaceAll("<[^>]*>", "").trim();
    }
}
