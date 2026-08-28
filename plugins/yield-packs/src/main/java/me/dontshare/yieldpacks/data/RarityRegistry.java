package me.dontshare.yieldpacks.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory catalog of every {@link Rarity} loaded from packs.yml. */
public final class RarityRegistry {

    private final Map<String, Rarity> rarities;

    public RarityRegistry(Map<String, Rarity> rarities) {
        this.rarities = new LinkedHashMap<>(rarities);
    }

    public Optional<Rarity> find(String id) {
        return Optional.ofNullable(rarities.get(id));
    }

    public Rarity getOrThrow(String id) {
        Rarity rarity = rarities.get(id);
        if (rarity == null) {
            throw new IllegalArgumentException("Unknown rarity id: " + id);
        }
        return rarity;
    }

    public Collection<Rarity> all() {
        return rarities.values();
    }
}
