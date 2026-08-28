package me.dontshare.yieldpacks.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory catalog of every {@link PackDefinition} loaded from packs.yml. */
public final class PackRegistry {

    private final Map<String, PackDefinition> packs;

    public PackRegistry(Map<String, PackDefinition> packs) {
        this.packs = new LinkedHashMap<>(packs);
    }

    public Optional<PackDefinition> find(String id) {
        return Optional.ofNullable(packs.get(id));
    }

    public PackDefinition getOrThrow(String id) {
        PackDefinition pack = packs.get(id);
        if (pack == null) {
            throw new IllegalArgumentException("Unknown pack id: " + id);
        }
        return pack;
    }

    /** Every pack, sorted by its configured display order. */
    public List<PackDefinition> all() {
        List<PackDefinition> sorted = new ArrayList<>(packs.values());
        sorted.sort(Comparator.comparingInt(PackDefinition::sortOrder));
        return sorted;
    }
}
