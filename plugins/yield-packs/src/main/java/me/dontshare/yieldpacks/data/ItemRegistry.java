package me.dontshare.yieldpacks.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory catalog of every {@link ItemDefinition} (pet) loaded from packs.yml. */
public final class ItemRegistry {

    private final Map<String, ItemDefinition> items;

    public ItemRegistry(Map<String, ItemDefinition> items) {
        this.items = new LinkedHashMap<>(items);
    }

    public Optional<ItemDefinition> find(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public ItemDefinition getOrThrow(String id) {
        ItemDefinition item = items.get(id);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item id: " + id);
        }
        return item;
    }

    public Collection<ItemDefinition> all() {
        return items.values();
    }
}
