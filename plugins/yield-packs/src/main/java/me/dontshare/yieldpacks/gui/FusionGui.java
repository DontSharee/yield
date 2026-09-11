package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.fusion.FusionService;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Lets a player combine 3 storage copies of one fusion tier into 1 of the
 * next (see {@link FusionService}). There is no unrestricted "fuse into
 * anything" entry point - {@link #open} always locks the whole screen to
 * one target tier, reached only via that tier's own physical machine (see
 * yield-zonemachines' Golden/Rainbow/Dark Matter walk-in triggers).
 */
public final class FusionGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final String AUTO_FUSE_PERMISSION = "yieldpacks.autofuse";
    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 45; // exclusive
    private static final int PREV_SLOT = 45;
    private static final int FUSE_ALL_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int AUTO_FUSE_SLOT = 51;
    private static final int NEXT_SLOT = 53;
    private static final int PAGE_SIZE = CONTENT_END - CONTENT_START;
    private static final int FUSE_COST = 3;

    private record FusionEntry(String itemId, ItemDefinition item, Rarity rarity, int remaining) {
    }

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final FusionService fusionService;
    private final GuiManager guiManager;
    private final ItemIconFactory iconFactory;

    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    /** Remembers which tier filter (if any) this player's own view is currently under, so an internal refresh (page turn, a fuse, toggling Auto Fuse) reopens the SAME filtered/unfiltered view rather than silently dropping back to "show everything". */
    private final Map<UUID, FusionTier> tierFilterByPlayer = new ConcurrentHashMap<>();

    public FusionGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                      Supplier<RarityRegistry> rarityRegistry, FusionService fusionService,
                      GuiManager guiManager, ItemIconFactory iconFactory) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.fusionService = fusionService;
        this.guiManager = guiManager;
        this.iconFactory = iconFactory;
    }

    /**
     * There is no unrestricted "fuse into anything" screen anymore - every
     * entry point into this GUI is one specific physical machine (Golden,
     * Rainbow, or Dark Matter - see yield-zonemachines' walk-in triggers),
     * and {@code targetTier} locks the whole screen to producing ONLY that
     * tier: the list shows just the base pets 3-deep toward it (never an
     * already-Golden pet's own path to Rainbow from the Golden machine),
     * and Fuse All ({@link #attemptFuseAll}) is likewise capped to that one
     * tier rather than cascading through every reachable one.
     */
    public void open(Player player, FusionTier targetTier) {
        UUID uuid = player.getUniqueId();
        tierFilterByPlayer.put(uuid, targetTier);
        PackPlayerProfile profile = store.getOrCreate(uuid);
        List<FusionEntry> entries = buildEntries(profile, targetTier);
        Page<FusionEntry> page = Page.of(entries, pageIndex.getOrDefault(uuid, 0), PAGE_SIZE);
        pageIndex.put(uuid, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Fusion (into " + targetTier.name() + ")");

        List<FusionEntry> items = page.items();
        for (int i = 0; i < items.size(); i++) {
            FusionEntry entry = items.get(i);
            builder.item(CONTENT_START + i, buildIcon(entry), (clicker, event) -> attemptFuse(clicker, entry.itemId()));
        }

        builder.fill(IntStream.range(0, 9), GuiIcons.filler());
        builder.fill(IntStream.range(CONTENT_END, TOTAL_ROWS * 9)
                .filter(slot -> slot != PREV_SLOT && slot != FUSE_ALL_SLOT && slot != CLOSE_SLOT
                        && slot != AUTO_FUSE_SLOT && slot != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()),
                (clicker, event) -> turnPage(clicker, page, -1));
        builder.item(FUSE_ALL_SLOT, buildFuseAllButton(), (clicker, event) -> attemptFuseAll(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(AUTO_FUSE_SLOT, buildAutoFuseButton(player, profile), (clicker, event) -> toggleAutoFuse(clicker));
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()),
                (clicker, event) -> turnPage(clicker, page, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, Page<FusionEntry> currentPage, int delta) {
        boolean canMove = delta < 0 ? currentPage.hasPrevious() : currentPage.hasNext();
        if (!canMove) {
            return;
        }
        pageIndex.put(player.getUniqueId(), currentPage.index() + delta);
        open(player, tierFilterByPlayer.get(player.getUniqueId()));
    }

    private void attemptFuse(Player player, String itemId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (!fusionService.canFuse(profile, itemId)) {
            int remaining = fusionService.remainingCount(profile, itemId);
            player.sendMessage(Text.parse("<red>Need <cost> to fuse - you have <remaining>.</red>",
                    Placeholder.unparsed("cost", String.valueOf(FUSE_COST)),
                    Placeholder.unparsed("remaining", String.valueOf(remaining))));
            return;
        }
        String resultId = fusionService.fuse(profile, itemId);
        store.save(player.getUniqueId());
        ItemDefinition resultItem = itemRegistry.get().find(resultId).orElse(null);
        Component resultName = resultItem != null ? taggedNameComponent(resultItem) : Component.text(resultId);
        playFuseSound(player, resultItem != null ? resultItem.fusionTier() : FusionTier.GOLDEN);
        // Placeholder.component inserts an ALREADY-BUILT Component, not raw
        // text MiniMessage re-parses - the previous version passed the tag+
        // legacy-coded name through Placeholder.parsed as a raw string, but
        // Text#parse only legacy-translates its own template argument, not a
        // placeholder's value, so the embedded "&6" etc. rendered as literal
        // text instead of color (see #taggedNameComponent, which strips the
        // legacy code and re-applies it via Text.parse BEFORE this point,
        // same fix yield-broadcasts' own petDisplayComponent already uses).
        player.sendMessage(Text.parse("<green>Fused into <result></green>!", Placeholder.component("result", resultName)));
        Bukkit.getPluginManager().callEvent(new PetFusedEvent(player, resultId));
        open(player, tierFilterByPlayer.get(player.getUniqueId()));
    }

    private void attemptFuseAll(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        FusionTier targetTier = tierFilterByPlayer.get(player.getUniqueId());
        List<String> results = fusionService.fuseAllToTier(profile, targetTier, FusionService.FUSE_ALL_CAP);
        if (results.isEmpty()) {
            player.sendMessage(Text.parse("<red>Nothing fusable right now.</red>"));
            open(player, tierFilterByPlayer.get(player.getUniqueId()));
            return;
        }
        store.save(player.getUniqueId());
        for (String resultId : results) {
            Bukkit.getPluginManager().callEvent(new PetFusedEvent(player, resultId));
        }
        String capNote = results.size() >= FusionService.FUSE_ALL_CAP ? " (capped - click again for more)" : "";
        player.sendMessage(Text.parse("<green>Fused <count> time(s)!</green>" + capNote,
                Placeholder.unparsed("count", String.valueOf(results.size()))));
        open(player, tierFilterByPlayer.get(player.getUniqueId()));
    }

    private void toggleAutoFuse(Player player) {
        if (!player.hasPermission(AUTO_FUSE_PERMISSION)) {
            player.sendMessage(Text.parse("<red>You don't have permission to use Auto Fuse.</red>"));
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAutoFuseEnabled(!profile.isAutoFuseEnabled());
        store.save(player.getUniqueId());
        player.sendMessage(Text.parse(profile.isAutoFuseEnabled()
                ? "<green>Auto Fuse enabled.</green>"
                : "<gray>Auto Fuse disabled.</gray>"));
        open(player, tierFilterByPlayer.get(player.getUniqueId()));
    }

    private ItemStack buildFuseAllButton() {
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.buttonName(ACCENT, "FUSE ALL"));
        MenuLore.button(
                "fusion",
                List.of(" &7Fuses everything you can,", " &7cascading through tiers", " &7in one click."),
                ACCENT,
                "Click to Fuse All"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildAutoFuseButton(Player viewer, PackPlayerProfile profile) {
        boolean hasPermission = viewer.hasPermission(AUTO_FUSE_PERMISSION);
        boolean enabled = profile.isAutoFuseEnabled();
        if (!hasPermission) {
            ItemBuilder locked = ItemBuilder.of(Material.GRAY_DYE).name("&7AUTO FUSE");
            MenuLore.info("fusion", List.of(" &7Buy &fAuto Fuse Pass&7 from /buy", " &7to unlock this, permanently."), ACCENT, List.of())
                    .forEach(locked::lore);
            return locked.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(enabled ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, "AUTO FUSE: " + (enabled ? "ON" : "OFF")));
        MenuLore.button(
                "fusion",
                List.of(" &7Automatically fuses everything", " &7fusable in the background."),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private List<FusionEntry> buildEntries(PackPlayerProfile profile, FusionTier targetTier) {
        List<FusionEntry> entries = new ArrayList<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (PetInstance pet : profile.getPets()) {
            itemIds.add(pet.getItemId());
        }
        for (String itemId : itemIds) {
            itemRegistry.get().find(itemId).ifPresent(item -> {
                if (item.fusionTier() == FusionTier.DARK_MATTER) {
                    return;
                }
                if (item.fusionTier().next() != targetTier) {
                    return;
                }
                int remaining = fusionService.remainingCount(profile, itemId);
                if (remaining < FUSE_COST) {
                    return;
                }
                Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                entries.add(new FusionEntry(itemId, item, rarity, remaining));
            });
        }
        entries.sort(Comparator
                .comparingInt((FusionEntry e) -> e.rarity() != null ? e.rarity().sortOrder() : 0).reversed()
                .thenComparingInt(e -> e.item().fusionTier().ordinal()));
        return entries;
    }

    /** This item's own plain name, with its fusion tier's tag (if any) prepended - see FusionTier#tag for why the name itself is never gradiented. */
    private String taggedName(ItemDefinition item) {
        String tag = item.fusionTier().tag();
        return tag == null ? item.displayName() : tag + " " + item.displayName();
    }

    /**
     * Same tag+name as {@link #taggedName}, but returned as a real,
     * correctly-colored {@link Component} instead of a raw string - for use
     * in a chat message (via {@code Placeholder.component}), never item
     * lore. Strips the name's own leading legacy code before splicing, same
     * fix yield-broadcasts' own {@code petDisplayComponent} already applies
     * for this exact tag+name shape.
     */
    /**
     * A distinct sound per resulting tier instead of no sound at all (the
     * previous version relied purely on the chat message) - an anvil for
     * Golden (literally "smithing something better"), a spellcast for
     * Rainbow (colorful and magical), and Dark Matter gets a deep, ominous
     * sculk bloom - eerie without being the jarring, ear-splitting kind of
     * loud (deliberately NOT the Warden's own sonic boom, which reads as
     * "danger," not "reward," and would get old fast at fusion's actual
     * frequency).
     */
    private void playFuseSound(Player player, FusionTier resultTier) {
        switch (resultTier) {
            case GOLDEN -> player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
            case RAINBOW -> player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.9f, 1.1f);
            case DARK_MATTER -> player.playSound(player.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.8f, 0.8f);
            case NORMAL -> {
                // Fusion never produces a NORMAL-tier result - unreachable, but the switch must stay exhaustive.
            }
        }
    }

    private Component taggedNameComponent(ItemDefinition item) {
        String tag = item.fusionTier().tag();
        String plain = Formatting.stripLeadingColorCodes(item.displayName());
        return tag != null
                ? Text.parse(tag + " <name>", Placeholder.unparsed("name", plain))
                : Text.parse(plain);
    }

    private ItemStack buildIcon(FusionEntry entry) {
        ItemDefinition item = entry.item();
        String accent = entry.rarity() != null ? "<" + entry.rarity().colorHex() + ">" : "<#FFFFFF>";
        FusionTier nextTier = item.fusionTier().next();
        String nextName = itemRegistry.get().find(nextTier.idFor(item.baseItemId()))
                .map(this::taggedName).orElse(nextTier.name());

        ItemBuilder builder = iconFactory.baseIcon(item).name(taggedName(item));
        MenuLore.button(
                "fusion",
                List.of(" &7Combine " + FUSE_COST + " of this pet", " &7into a stronger fused form."),
                accent,
                "Click to Fuse"
        ).forEach(builder::lore);
        return builder
                .lore("")
                .lore("&7Owned: &f" + entry.remaining() + " &7/ &f" + FUSE_COST)
                .lore("&7Fuses into: " + nextName)
                .amount(Math.max(1, Math.min(64, entry.remaining())))
                .hideAttributes()
                .build();
    }
}
