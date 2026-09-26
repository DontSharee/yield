package me.dontshare.yieldanalytics.admin;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldanalytics.data.AnalyticsProfile;
import me.dontshare.yieldanalytics.web.Access;
import me.dontshare.yieldanalytics.webhook.WebhookService;
import me.dontshare.yieldcore.admin.PlayerEdits;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import net.kyori.adventure.text.Component;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Everything the site changes about a player, and the record of it.
 * <p>
 * Stats go through {@link PlayerEdits} - the owning plugin's own setters,
 * applied to the cached record of someone online or the stored one of
 * someone who isn't. Inventory and kicks need the player online and act on
 * them directly. Every change is written to {@code analytics_edits} with who
 * made it, the value before and after, and how to undo it.
 */
public final class EditService {

    private static final String RESTORE_ITEM = "inventory.restore";

    private final JavaPlugin plugin;
    private final PlayerDataStore<AnalyticsProfile> analyticsStore;
    private final PlayerDirectory directory;
    private final WebhookService webhooks;
    private final MongoCollection<Document> log;

    public EditService(JavaPlugin plugin, PlayerDataStore<AnalyticsProfile> analyticsStore, ActivityStore activity,
                       PlayerDirectory directory, WebhookService webhooks) {
        this.plugin = plugin;
        this.analyticsStore = analyticsStore;
        this.directory = directory;
        this.webhooks = webhooks;
        this.log = activity.database().getCollection("analytics_edits");
    }

    /** Database thread, once. */
    public void ensureIndexes() {
        log.createIndex(Indexes.descending("t"));
        log.createIndex(Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.descending("t")));
    }

    // ------------------------------------------------------------------ reading

    /** What can be edited, and how - the editor builds its controls from this. */
    public List<Map<String, Object>> schema() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayerEdits.Stat<?> stat : PlayerEdits.stats()) {
            if (stat.id().equals("pets.restore")) {
                continue; // undo only
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", stat.id());
            row.put("label", stat.label());
            row.put("group", stat.group());
            row.put("kind", stat.kind().name().toLowerCase(java.util.Locale.ROOT));
            row.put("min", stat.min());
            row.put("max", stat.max());
            row.put("hint", stat.hint());
            List<Map<String, Object>> options = new ArrayList<>();
            for (PlayerEdits.Option option : stat.options().get()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", option.value());
                o.put("label", option.label());
                if (option.group() != null) {
                    o.put("group", option.group());
                }
                options.add(o);
            }
            row.put("options", options);
            out.add(row);
        }
        return out;
    }

    public Map<String, String> values(UUID id) {
        return await(PlayerEdits.read(id));
    }

    public Object pets(UUID id) {
        return await(PlayerEdits.view(id, "pets"));
    }

    /** Live while they're online; otherwise as they logged out. */
    public Map<String, Object> inventory(UUID id) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> live = sync(() -> {
            Player player = Bukkit.getPlayer(id);
            return player != null ? InventoryView.rows(player) : null;
        });
        if (live != null) {
            out.put("live", true);
            out.put("items", live);
            return out;
        }
        String[] stored = await(analyticsStore.read(id, profile -> new String[]{profile.getInventoryJson(), String.valueOf(profile.getInventoryAt())}));
        out.put("live", false);
        out.put("items", stored == null ? List.of() : InventoryView.parse(stored[0]));
        out.put("savedAt", stored == null ? 0L : Long.parseLong(stored[1]));
        return out;
    }

    public List<Map<String, Object>> history(UUID id, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document doc : log.find(id == null ? new Document() : Filters.eq("uuid", id.toString()))
                .sort(Sorts.descending("t")).limit(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", doc.getObjectId("_id").toHexString());
            row.put("t", doc.getDate("t").getTime());
            row.put("actor", doc.getString("actor"));
            row.put("uuid", doc.getString("uuid"));
            row.put("player", doc.getString("player"));
            row.put("stat", doc.getString("stat"));
            row.put("summary", doc.getString("summary"));
            row.put("where", doc.getString("where"));
            row.put("canUndo", doc.get("undo") != null && doc.get("undoneAt") == null);
            row.put("undone", doc.get("undoneAt") != null);
            row.put("undoneBy", doc.getString("undoneBy"));
            row.put("undoOf", doc.getString("undoOf"));
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ changing

    /** Sets one stat. */
    public Map<String, Object> edit(Access.Caller caller, UUID id, String statId, String value) {
        return edit(caller, id, statId, value, null);
    }

    private Map<String, Object> edit(Access.Caller caller, UUID id, String statId, String value, String undoOf) {
        String player = requirePlayer(id);
        if (statId.equals(RESTORE_ITEM)) {
            return restoreItem(caller, id, player, value, undoOf);
        }
        PlayerEdits.Stat<?> stat = PlayerEdits.find(statId);
        if (stat == null) {
            throw new IllegalArgumentException("No such stat: " + statId);
        }
        String subject = statId.equals("pets.remove") ? petName(id, value) : null;
        PlayerEdits.Result result = await(PlayerEdits.apply(id, statId, value));
        if (stat.kind() != PlayerEdits.Kind.ACTION && java.util.Objects.equals(result.before(), result.after())) {
            throw new IllegalArgumentException("That's already the value - nothing changed.");
        }
        String summary = summary(stat, value, result, subject);
        Document undo = result.undo() == null ? null
                : new Document("stat", result.undo().stat()).append("value", result.undo().value());
        String editId = record(caller, id, player, statId, summary, result.where().name().toLowerCase(java.util.Locale.ROOT), undo, undoOf,
                new Document("value", value).append("before", result.before()).append("after", result.after()));
        refreshDirectory(id);
        webhooks.edit(caller.name(), player, summary);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", editId);
        out.put("summary", summary);
        out.put("before", result.before());
        out.put("after", result.after());
        out.put("where", result.where() == PlayerDataStore.EditTarget.LIVE ? "live" : "database");
        return out;
    }

    /** Takes one item out of an online player's inventory. */
    public Map<String, Object> removeItem(Access.Caller caller, UUID id, int slot) {
        String player = requirePlayer(id);
        if (slot < 0 || slot > 40) {
            throw new IllegalArgumentException("No such slot.");
        }
        String[] taken = sync(() -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                throw new IllegalArgumentException("Their inventory can only be changed while they're online.");
            }
            ItemStack item = online.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                throw new IllegalArgumentException("That slot is already empty.");
            }
            String label = item.getAmount() + "× " + InventoryView.pretty(item.getType().getKey().getKey());
            String saved = InventoryView.serialize(item);
            online.getInventory().setItem(slot, null);
            return new String[]{label, saved};
        });
        String summary = "Removed " + taken[0] + " from slot " + slot;
        Document undo = new Document("stat", RESTORE_ITEM).append("value", slot + "|" + taken[1]);
        String editId = record(caller, id, player, "inventory.remove", summary, "live", undo, null, new Document("slot", slot));
        webhooks.edit(caller.name(), player, summary);
        return Map.of("id", editId, "summary", summary);
    }

    private Map<String, Object> restoreItem(Access.Caller caller, UUID id, String player, String value, String undoOf) {
        int bar = value.indexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("Not a saved item.");
        }
        int slot = Integer.parseInt(value.substring(0, bar));
        ItemStack item = InventoryView.deserialize(value.substring(bar + 1));
        String placed = sync(() -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                throw new IllegalArgumentException("They need to be online to get the item back.");
            }
            ItemStack current = online.getInventory().getItem(slot);
            if (current == null || current.getType().isAir()) {
                online.getInventory().setItem(slot, item);
                return "slot " + slot;
            }
            Map<Integer, ItemStack> left = online.getInventory().addItem(item);
            if (!left.isEmpty()) {
                throw new IllegalArgumentException("Their inventory is full.");
            }
            return "their inventory";
        });
        String summary = "Put back " + item.getAmount() + "× " + InventoryView.pretty(item.getType().getKey().getKey()) + " into " + placed;
        String editId = record(caller, id, player, RESTORE_ITEM, summary, "live", null, undoOf, new Document());
        webhooks.edit(caller.name(), player, summary);
        return Map.of("id", editId, "summary", summary);
    }

    public Map<String, Object> kick(Access.Caller caller, UUID id, String reason) {
        String player = requirePlayer(id);
        String text = reason == null || reason.isBlank() ? "Kicked by an admin." : reason.trim();
        if (text.length() > 200) {
            throw new IllegalArgumentException("Keep the reason under 200 characters.");
        }
        sync(() -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                throw new IllegalArgumentException("They're not online.");
            }
            online.kick(Component.text(text));
            return null;
        });
        String summary = "Kicked: " + text;
        String editId = record(caller, id, player, "kick", summary, "live", null, null, new Document());
        webhooks.edit(caller.name(), player, summary);
        return Map.of("id", editId, "summary", summary);
    }

    /** Reverses one logged edit, once. */
    public Map<String, Object> undo(Access.Caller caller, String editId) {
        if (!ObjectId.isValid(editId)) {
            throw new IllegalArgumentException("No such edit.");
        }
        Document doc = log.find(Filters.eq("_id", new ObjectId(editId))).first();
        if (doc == null) {
            throw new NoSuchElementException("No such edit.");
        }
        Document undo = doc.get("undo", Document.class);
        if (undo == null) {
            throw new IllegalArgumentException("That can't be undone.");
        }
        // Claimed first, so two clicks can't undo it twice.
        Document claimed = log.findOneAndUpdate(Filters.and(Filters.eq("_id", doc.getObjectId("_id")), Filters.eq("undoneAt", null)),
                Updates.combine(Updates.set("undoneAt", new Date()), Updates.set("undoneBy", caller.name())));
        if (claimed == null) {
            throw new IllegalArgumentException("Already undone.");
        }
        try {
            return edit(caller, UUID.fromString(doc.getString("uuid")), undo.getString("stat"), undo.getString("value"), editId);
        } catch (RuntimeException e) {
            log.updateOne(Filters.eq("_id", doc.getObjectId("_id")), Updates.combine(Updates.unset("undoneAt"), Updates.unset("undoneBy")));
            throw e;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String record(Access.Caller caller, UUID id, String player, String stat, String summary, String where,
                          Document undo, String undoOf, Document details) {
        Document doc = new Document("t", new Date())
                .append("actor", caller.name())
                .append("ip", caller.ip())
                .append("uuid", id.toString())
                .append("player", player)
                .append("stat", stat)
                .append("summary", summary)
                .append("where", where)
                .append("details", details);
        if (undo != null) {
            doc.append("undo", undo);
        }
        if (undoOf != null) {
            doc.append("undoOf", undoOf);
        }
        log.insertOne(doc);
        plugin.getLogger().info("[edit] " + caller.name() + " (" + caller.ip() + ") → " + player + ": " + summary);
        return doc.getObjectId("_id").toHexString();
    }

    private String requirePlayer(UUID id) {
        String name = directory.nameOf(id);
        if (name == null) {
            directory.refresh(id);
            name = directory.nameOf(id);
        }
        if (name == null) {
            throw new NoSuchElementException("No player with that id has ever joined.");
        }
        return name;
    }

    private void refreshDirectory(UUID id) {
        CompletableFuture.runAsync(() -> directory.refresh(id));
    }

    private String petName(UUID id, String instanceId) {
        try {
            Object view = pets(id);
            if (view instanceof Map<?, ?> map && map.get("pets") instanceof List<?> pets) {
                for (Object entry : pets) {
                    if (entry instanceof Map<?, ?> pet && instanceId.equals(pet.get("id"))) {
                        return (Boolean.TRUE.equals(pet.get("shiny")) ? "Shiny " : "") + pet.get("name");
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Named by id instead.
        }
        return null;
    }

    private static String summary(PlayerEdits.Stat<?> stat, String value, PlayerEdits.Result result, String subject) {
        return switch (stat.id()) {
            case "pets.give" -> "Gave a pet: " + optionLabel(stat, value.replace(":shiny", "")) + (value.endsWith(":shiny") ? " (shiny)" : "");
            case "pets.remove" -> "Removed a pet: " + (subject != null ? subject : value);
            case "pets.restore" -> "Put back a removed pet";
            default -> stat.label() + ": " + show(stat, result.before()) + " → " + show(stat, result.after());
        };
    }

    private static String show(PlayerEdits.Stat<?> stat, String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        if (stat.kind() == PlayerEdits.Kind.AMOUNT) {
            try {
                return Formatting.format(new java.math.BigInteger(value));
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if (stat.kind() == PlayerEdits.Kind.CHOICE) {
            return optionLabel(stat, value);
        }
        return value;
    }

    private static String optionLabel(PlayerEdits.Stat<?> stat, String value) {
        for (PlayerEdits.Option option : stat.options().get()) {
            if (option.value().equals(value)) {
                return option.label();
            }
        }
        return value;
    }

    private <T> T sync(Callable<T> work) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, work).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The server didn't answer in time - try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("The server didn't answer in time - try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
    }
}
