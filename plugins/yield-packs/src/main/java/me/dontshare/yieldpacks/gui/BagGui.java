package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.gui.SortButton;
import me.dontshare.yieldcore.gui.SortOption;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The Bag screen - two independently-paginated grids in one GUI. Row 1 pages
 * through only your currently-equipped pets (5 at a time); the storage grid
 * below pages through everything else you own. Equipped pets are individual
 * instances, not stacks - equipping one of 22 owned copies of a pet takes
 * exactly one out of storage (leaving 21) and gives it its own slot in the
 * equip strip; equipping the same type again takes a second slot rather
 * than stacking onto the first.
 */
public final class BagGui {

    private static final int TOTAL_ROWS = 6;
    private static final int EQUIPPED_PAGE_SIZE = 5;
    private static final int STORAGE_PAGE_SIZE = 36; // rows 2-5

    private record BagEntry(String itemId, ItemDefinition item, Rarity rarity, int count, long lastObtainedAt) {
    }

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final EquipmentService equipmentService;
    private final GuiManager guiManager;
    private final ItemIconFactory iconFactory;
    private final PetDisplayService petDisplayService;
    private final SortButton<BagEntry> sortButton;

    private final Map<UUID, Integer> equippedPageIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> storagePageIndex = new ConcurrentHashMap<>();

    public BagGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                  Supplier<RarityRegistry> rarityRegistry, EquipmentService equipmentService,
                  GuiManager guiManager, ItemIconFactory iconFactory, PetDisplayService petDisplayService) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.equipmentService = equipmentService;
        this.guiManager = guiManager;
        this.iconFactory = iconFactory;
        this.petDisplayService = petDisplayService;
        this.sortButton = new SortButton<>(List.of(
                new SortOption<>("Value: High to Low",
                        Comparator.comparingDouble((BagEntry e) -> e.item().valuePerSecond()).reversed()),
                new SortOption<>("Rarity: High to Low",
                        Comparator.comparingInt((BagEntry e) -> e.rarity() != null ? e.rarity().sortOrder() : 0).reversed()),
                new SortOption<>("Newest to Oldest",
                        Comparator.comparingLong(BagEntry::lastObtainedAt).reversed())));
    }

    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(uuid);

        List<BagEntry> equippedEntries = buildEquippedEntries(profile);
        List<BagEntry> storageEntries = sortButton.sorted(player, buildStorageEntries(profile));

        Page<BagEntry> equippedPage = Page.of(equippedEntries, equippedPageIndex.getOrDefault(uuid, 0), EQUIPPED_PAGE_SIZE);
        Page<BagEntry> storagePage = Page.of(storageEntries, storagePageIndex.getOrDefault(uuid, 0), STORAGE_PAGE_SIZE);
        equippedPageIndex.put(uuid, equippedPage.index());
        storagePageIndex.put(uuid, storagePage.index());

        var builder = Gui.builder(TOTAL_ROWS, "<#4BD9FF><bold>Bag</bold> <gray>(" +
                equippedEntries.size() + "/" + equipmentService.getEquipCap() + " equipped)");

        // Row 1: equipped strip, 5 slots (2-6) with its own prev/next arrows.
        builder.fill(IntStream.of(0, 8), GuiIcons.filler());
        builder.item(1, GuiIcons.pageArrow(false, equippedPage.hasPrevious()),
                (clicker, event) -> turnPage(clicker, equippedPageIndex, equippedPage, -1));
        builder.item(7, GuiIcons.pageArrow(true, equippedPage.hasNext()),
                (clicker, event) -> turnPage(clicker, equippedPageIndex, equippedPage, 1));
        for (int i = 0; i < equippedPage.items().size(); i++) {
            BagEntry entry = equippedPage.items().get(i);
            builder.item(2 + i, buildIcon(entry, true), (clicker, event) -> unequip(clicker, entry.itemId()));
        }
        // Unused equip slots (2-6) and storage slots (9-44) are left genuinely
        // empty rather than filler-padded, so the grid only ever shows real
        // content - filler is reserved for the fixed border/control slots.

        // Rows 2-5: storage grid.
        List<BagEntry> storageItems = storagePage.items();
        for (int i = 0; i < storageItems.size(); i++) {
            BagEntry entry = storageItems.get(i);
            builder.item(9 + i, buildIcon(entry, false), (clicker, event) -> equip(clicker, entry.itemId()));
        }

        // Row 6: controls.
        builder.fill(IntStream.of(45, 47, 49, 51, 53), GuiIcons.filler());
        builder.item(46, GuiIcons.pageArrow(false, storagePage.hasPrevious()),
                (clicker, event) -> turnPage(clicker, storagePageIndex, storagePage, -1));
        builder.item(48, sortButton.buildIcon(player), (clicker, event) -> {
            sortButton.cycle(clicker);
            open(clicker);
        });
        builder.item(50, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(52, GuiIcons.pageArrow(true, storagePage.hasNext()),
                (clicker, event) -> turnPage(clicker, storagePageIndex, storagePage, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, Map<UUID, Integer> pageIndex, Page<BagEntry> currentPage, int delta) {
        boolean canMove = delta < 0 ? currentPage.hasPrevious() : currentPage.hasNext();
        if (!canMove) {
            return;
        }
        pageIndex.put(player.getUniqueId(), currentPage.index() + delta);
        open(player);
    }

    /** One entry per equipped instance (a type equipped twice yields two entries, each count=1 - never stacked). */
    private List<BagEntry> buildEquippedEntries(PackPlayerProfile profile) {
        List<BagEntry> entries = new ArrayList<>();
        for (String itemId : profile.getEquippedItemIds()) {
            itemRegistry.get().find(itemId).ifPresent(item -> {
                Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                long lastObtained = profile.getLastObtainedAt().getOrDefault(itemId, 0L);
                entries.add(new BagEntry(itemId, item, rarity, 1, lastObtained));
            });
        }
        return entries;
    }

    /** Owned count minus however many of that type are currently equipped - a type fully equipped out disappears from storage. */
    private List<BagEntry> buildStorageEntries(PackPlayerProfile profile) {
        List<BagEntry> entries = new ArrayList<>();
        List<String> equippedList = profile.getEquippedItemIds();
        for (var owned : profile.getOwnedItems().entrySet()) {
            String itemId = owned.getKey();
            int equippedCount = (int) equippedList.stream().filter(itemId::equals).count();
            int remaining = owned.getValue() - equippedCount;
            if (remaining <= 0) {
                continue;
            }
            itemRegistry.get().find(itemId).ifPresent(item -> {
                Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                long lastObtained = profile.getLastObtainedAt().getOrDefault(itemId, 0L);
                entries.add(new BagEntry(itemId, item, rarity, remaining, lastObtained));
            });
        }
        return entries;
    }

    private void equip(Player player, String itemId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int ownedCount = profile.getOwnedItems().getOrDefault(itemId, 0);
        if (equipmentService.manualEquip(profile, itemId, ownedCount)) {
            player.sendMessage(Text.parse("<green>Equipped.</green>"));
            petDisplayService.refresh(player);
        } else if (profile.getEquippedItemIds().size() >= equipmentService.getEquipCap()) {
            player.sendMessage(Text.parse("<red>You're at your equip cap (<cap>). Unequip something first.</red>",
                    Placeholder.unparsed("cap", String.valueOf(equipmentService.getEquipCap()))));
        } else {
            player.sendMessage(Text.parse("<red>You don't have any more of this pet to equip.</red>"));
        }
        open(player);
    }

    private void unequip(Player player, String itemId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        equipmentService.manualUnequip(profile, itemId);
        player.sendMessage(Text.parse("<gray>Unequipped.</gray>"));
        petDisplayService.refresh(player);
        open(player);
    }

    private ItemStack buildIcon(BagEntry entry, boolean equipped) {
        ItemDefinition item = entry.item();
        String accent = entry.rarity() != null ? "<" + entry.rarity().colorHex() + ">" : "<#FFFFFF>";
        String rarityName = entry.rarity() != null ? entry.rarity().displayName() : "&7Unknown";

        List<String> data = new ArrayList<>(List.of(
                "&7Rarity: &f" + rarityName,
                "&7Value: &f$" + Formatting.format(item.valuePerSecond()) + "&7/sec"
        ));
        if (!equipped) {
            data.add("&7Owned: &f" + entry.count());
        }
        data.add("&7Click: &f" + (equipped ? "Unequip" : "Equip"));

        ItemBuilder builder = iconFactory.baseIcon(item).name(item.displayName() + (equipped ? " &a[Equipped]" : ""));
        MenuLore.info("pet", List.of(" &7A loyal &fcompanion&7!"), accent, data).forEach(builder::lore);
        return builder
                .amount(equipped ? 1 : Math.max(1, Math.min(64, entry.count())))
                .hideAttributes()
                .build();
    }
}
