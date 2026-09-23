package me.dontshare.yieldpacks.mastery;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * "/masteries" - PS99-style: row 0 selects one of the 4 tracks, the content
 * region below shows that track's own level/XP header plus a paginated list
 * of its discrete {@link MasteryPerk}s (green/checked once unlocked, gray/
 * locked otherwise). One persistent inventory for the whole session -
 * switching tracks or turning a page repaints in place via {@link Gui#set},
 * never opens a second Gui - same architecture as {@code StoreHubGui}
 * (built and shipped earlier this session), reused here rather than
 * registered as one of its {@code StoreCategory} tabs - Masteries is its
 * own screen, not part of the Buycraft-style Store.
 */
public final class MasteryGui {

    private static final int TOTAL_ROWS = 6;
    /** One per track, centred in the top row - see GuiLayout. */
    private static final int[] TRACK_SLOTS = GuiLayout.centeredRow(0, MasteryType.values().length);
    private static final int HEADER_SLOT = 13;
    /** Rows 2-4: the area perks are drawn into, and how many fit on a page (three centred rows of seven). */
    private static final List<Integer> PERK_AREA = IntStream.range(18, 45).boxed().toList();
    private static final int PERKS_PER_PAGE = GuiLayout.capacity(3);
    private static final int PREV_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final MasteryService service;
    private final GuiManager guiManager;
    private final Map<UUID, MasteryType> selectedTrack = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public MasteryGui(PlayerDataStore<PackPlayerProfile> store, MasteryService service, GuiManager guiManager) {
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        MasteryType type = selectedTrack.getOrDefault(player.getUniqueId(), MasteryType.values()[0]);
        selectedTrack.put(player.getUniqueId(), type);
        pageIndex.put(player.getUniqueId(), 0);

        var builder = Gui.builder(TOTAL_ROWS, "Masteries");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());
        placeTrackButtons(builder::item, type);
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        Gui gui = builder.build();
        renderContent(player, gui, type);
        guiManager.open(player, gui);
    }

    private void selectTrack(Player player, MasteryType type) {
        selectedTrack.put(player.getUniqueId(), type);
        pageIndex.put(player.getUniqueId(), 0);
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        placeTrackButtons(gui::set, type);
        renderContent(player, gui, type);
    }

    private void turnPage(Player player, int delta) {
        UUID id = player.getUniqueId();
        pageIndex.put(id, Math.max(0, pageIndex.getOrDefault(id, 0) + delta));
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        renderContent(player, gui, selectedTrack.getOrDefault(id, MasteryType.values()[0]));
    }

    private void placeTrackButtons(TrackButtonPlacer place, MasteryType selected) {
        MasteryType[] types = MasteryType.values();
        for (int i = 0; i < types.length && i < TRACK_SLOTS.length; i++) {
            MasteryType type = types[i];
            place.place(TRACK_SLOTS[i], buildTrackButton(type, type == selected), (clicker, e) -> selectTrack(clicker, type));
        }
    }

    private void renderContent(Player player, Gui gui, MasteryType type) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        gui.set(HEADER_SLOT, buildHeaderIcon(profile, type), null);

        List<MasteryPerk> perks = service.allPerks(type);
        int page = Math.min(Math.max(0, pageIndex.getOrDefault(player.getUniqueId(), 0)), Math.max(0, (perks.size() - 1) / PERKS_PER_PAGE));
        pageIndex.put(player.getUniqueId(), page);
        int start = page * PERKS_PER_PAGE;
        int shown = Math.max(0, Math.min(PERKS_PER_PAGE, perks.size() - start));

        for (int slot : PERK_AREA) {
            gui.set(slot, GuiIcons.filler(), null);
        }
        int[] slots = GuiLayout.centered(PERK_AREA.get(0) / 9, shown);
        for (int i = 0; i < shown; i++) {
            gui.set(slots[i], buildPerkIcon(profile, type, perks.get(start + i)), null);
        }

        boolean hasPrevious = page > 0;
        boolean hasNext = start + PERKS_PER_PAGE < perks.size();
        gui.set(PREV_SLOT, GuiIcons.pageArrow(false, hasPrevious), (clicker, e) -> turnPage(clicker, -1));
        gui.set(NEXT_SLOT, GuiIcons.pageArrow(true, hasNext), (clicker, e) -> turnPage(clicker, 1));
    }

    private ItemStack buildTrackButton(MasteryType type, boolean selected) {
        ItemBuilder builder = ItemBuilder.of(iconFor(type))
                .name(selected ? MenuLore.buttonName(MenuLore.ACCENT, prettyName(type)) : "&7" + prettyName(type));
        MenuLore.info("mastery", List.of(), MenuLore.ACCENT, selected ? List.of("Selected") : List.of("Click to View"))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildHeaderIcon(PackPlayerProfile profile, MasteryType type) {
        int level = service.levelOf(profile, type);
        long into = service.xpIntoCurrentLevel(profile, type);
        long needed = service.xpForLevel(level);
        ItemBuilder builder = ItemBuilder.of(iconFor(type))
                .name(MenuLore.infoName(MenuLore.ACCENT, prettyName(type) + " Mastery"));
        MenuLore.info(
                "mastery",
                List.of(" &7" + descriptionFor(type)),
                MenuLore.ACCENT,
                List.of(
                        "Level: &f" + level,
                        "Progress: " + MenuLore.progress(into, needed) + " &7xp"
                )
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildPerkIcon(PackPlayerProfile profile, MasteryType type, MasteryPerk perk) {
        boolean unlocked = service.levelOf(profile, type) >= perk.level();
        ItemBuilder builder = ItemBuilder.of(unlocked ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE)
                .name((unlocked ? "&a&l" : "&7&l") + perk.displayName() + " &8[Lv. " + perk.level() + "]");
        List<String> data = new java.util.ArrayList<>(perk.description());
        MenuLore.info("mastery", data, unlocked ? "<green>" : "<gray>",
                List.of(unlocked ? "&a✔ Unlocked" : "&7✖ Locked")).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private Material iconFor(MasteryType type) {
        return switch (type) {
            case PACKS -> Material.CHEST;
            case REFINERY -> Material.NETHER_STAR;
            case MINING -> Material.DIAMOND_PICKAXE;
            case COMBAT -> Material.DIAMOND_SWORD;
        };
    }

    private String prettyName(MasteryType type) {
        return switch (type) {
            case PACKS -> "Eggs";
            case REFINERY -> "Pet Refinery";
            case MINING -> "Mining";
            case COMBAT -> "Combat";
        };
    }

    private String descriptionFor(MasteryType type) {
        return switch (type) {
            case PACKS -> "Earned by hatching eggs.";
            case REFINERY -> "Earned by rolling on the Pet Enchanting Table.";
            case MINING -> "Earned by mining ore.";
            case COMBAT -> "Earned by destroying ore cubes.";
        };
    }

    /** {@code place} is either a fresh {@code GuiBuilder::item} (first open) or a live {@code Gui::set} (switching tracks) - same 3-arg shape either way. */
    @FunctionalInterface
    private interface TrackButtonPlacer {
        void place(int slot, ItemStack item, me.dontshare.yieldcore.gui.GuiClickHandler handler);
    }
}
