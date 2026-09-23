package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.gui.SortButton;
import me.dontshare.yieldcore.gui.SortOption;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.pet.PetLabels;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.event.PetUnequippedEvent;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.leveling.PetLevelingService;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.pet.PetItemHandler;
import me.dontshare.yieldpacks.pet.PetWithdrawItem;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import me.dontshare.yieldpacks.petenchant.PetEnchantLore;
import me.dontshare.yieldpacks.player.AttackMode;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.SendMode;
import me.dontshare.yieldpacks.roll.ExistsCounterStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * One Bag slot. {@code representative} is the exact instance equip/
     * delete/feed actually act on - for a baseline stack ({@code
     * stack=true}, several interchangeable level-1 copies grouped into one
     * slot showing a count) it's an arbitrary member of that group; for an
     * individually shown pet (equipped, or a storage pet with real
     * progress) it's that exact instance.
     */
    private record BagEntry(String itemId, ItemDefinition item, Rarity rarity, int count, long lastObtainedAt,
                             PetInstance representative, double effectiveDamage, boolean stack) {
    }

    private static final String ACCENT = "<#4BD9FF>";
    private static final String AUTO_MODE_PERMISSION = SendMode.AUTO_PERMISSION;
    /** Kept in sync with yield-zones' own PetCombatController#PREMIUM_AUTO_MODE_PERMISSION (no compile-time link between the two modules - see YieldPacks' composable-provider pattern for why). */
    private static final String PREMIUM_AUTO_MODE_PERMISSION = SendMode.PREMIUM_AUTO_PERMISSION;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final EquipmentService equipmentService;
    private final GuiManager guiManager;
    private final ItemIconFactory iconFactory;
    private final PetDisplayService petDisplayService;
    private final DeleteByRarityGui deleteByRarityGui;
    private final PetLevelingService petLevelingService;
    private final PetWithdrawItem withdrawItem;
    private final ExistsCounterStore existsCounterStore;
    private final PetItemHandler applyHandlers;
    private final Supplier<PetEnchantContent> petEnchantContent;
    private final SortButton<BagEntry> sortButton;

    private final Map<UUID, Integer> equippedPageIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> storagePageIndex = new ConcurrentHashMap<>();

    public BagGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                  Supplier<RarityRegistry> rarityRegistry, EquipmentService equipmentService,
                  GuiManager guiManager, ItemIconFactory iconFactory, PetDisplayService petDisplayService,
                  DeleteByRarityGui deleteByRarityGui, PetLevelingService petLevelingService,
                  PetWithdrawItem withdrawItem, ExistsCounterStore existsCounterStore, PetItemHandler applyHandlers,
                  Supplier<PetEnchantContent> petEnchantContent) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.equipmentService = equipmentService;
        this.guiManager = guiManager;
        this.iconFactory = iconFactory;
        this.petDisplayService = petDisplayService;
        this.deleteByRarityGui = deleteByRarityGui;
        this.petLevelingService = petLevelingService;
        this.withdrawItem = withdrawItem;
        this.existsCounterStore = existsCounterStore;
        this.applyHandlers = applyHandlers;
        this.petEnchantContent = petEnchantContent;
        this.sortButton = new SortButton<>(List.of(
                new SortOption<>("Damage: High to Low",
                        Comparator.comparingDouble(BagEntry::effectiveDamage).reversed()),
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

        // Feeding candy/a held item to a pet means clicking its icon while
        // holding the item - the player has to be able to freely switch
        // what's in their hand (or grab a different one) without closing
        // this screen first.
        var builder = Gui.builder(TOTAL_ROWS, "Bag (" +
                equippedEntries.size() + "/" + equipmentService.getEquipCap(profile) + " equipped)")
                .allowPlayerInventoryInteraction();

        // Row 1: equipped strip, 5 slots (2-6) with its own prev/next arrows.
        // Slot 0 hosts the Send/Attack-mode (single vs. all) toggle; slot 8
        // hosts the "Delete by Rarity" shortcut - both overflow here rather
        // than crowding a 6th/7th, unevenly-spaceable button into row 6,
        // which is already exactly full at 5 evenly-spaced slots.
        builder.item(0, buildAttackModeToggle(profile), (clicker, event) -> toggleAttackMode(clicker));
        builder.item(8, buildDeleteByRarityButton(), (clicker, event) -> deleteByRarityGui.open(clicker));
        builder.item(1, GuiIcons.pageArrow(false, equippedPage.hasPrevious()),
                (clicker, event) -> turnPage(clicker, equippedPageIndex, equippedPage, -1));
        builder.item(7, GuiIcons.pageArrow(true, equippedPage.hasNext()),
                (clicker, event) -> turnPage(clicker, equippedPageIndex, equippedPage, 1));
        for (int i = 0; i < equippedPage.items().size(); i++) {
            BagEntry entry = equippedPage.items().get(i);
            builder.item(2 + i, buildIcon(entry, true), (clicker, event) -> {
                if (!applyHeldItem(clicker, entry.representative(), event)) {
                    unequip(clicker, entry.representative().getInstanceId());
                }
            });
        }
        // Unused equip slots (2-6) and storage slots (9-44) are left genuinely
        // empty rather than filler-padded, so the grid only ever shows real
        // content - filler is reserved for the fixed border/control slots.

        // Rows 2-5: storage grid. Left-click (or any non-shift-right click)
        // equips one; shift+right-click deletes one instead.
        List<BagEntry> storageItems = storagePage.items();
        for (int i = 0; i < storageItems.size(); i++) {
            BagEntry entry = storageItems.get(i);
            builder.item(9 + i, buildIcon(entry, false), (clicker, event) -> {
                if (applyHeldItem(clicker, entry.representative(), event)) {
                    return;
                }
                if (event.isShiftClick() && event.isRightClick()) {
                    deleteOne(clicker, entry.representative().getInstanceId());
                } else if (event.isRightClick()) {
                    withdraw(clicker, entry);
                } else {
                    equip(clicker, entry.representative().getInstanceId());
                }
            });
        }

        // Row 6: controls, evenly spaced with close dead-center. Slot 46
        // (otherwise plain filler) hosts the permission-gated Auto Mode
        // toggle instead, and slot 50 hosts Equip Best - reusing already-
        // inert slots rather than disturbing the 45/47/49/51/53 even-spacing
        // rule. Slot 51 (Fusion) reverted to plain filler - fusing is now
        // only reachable at its own tier-locked physical machine (Golden/
        // Rainbow/Dark Matter - see yield-zonemachines), not from here.
        builder.fill(IntStream.of(48, 51, 52), GuiIcons.filler());
        builder.item(46, buildSendModeToggle(player, profile), (clicker, event) -> toggleSendMode(clicker));
        builder.item(45, GuiIcons.pageArrow(false, storagePage.hasPrevious()),
                (clicker, event) -> turnPage(clicker, storagePageIndex, storagePage, -1));
        builder.item(47, sortButton.buildIcon(player), (clicker, event) -> {
            sortButton.cycle(clicker);
            open(clicker);
        });
        builder.item(49, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(50, buildEquipBestButton(), (clicker, event) -> equipBest(clicker));
        builder.item(53, GuiIcons.pageArrow(true, storagePage.hasNext()),
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

    /**
     * One entry per equipped instance (a type equipped twice yields two
     * entries, each count=1 - never stacked, and always shown individually
     * so its own level is visible), sorted strongest-first - matching
     * {@code PetCombatController#assignNextPetTo}'s own highest-damage-first
     * preference for Single-send, so what a player sees here always lines
     * up with which pet actually gets sent next.
     */
    private List<BagEntry> buildEquippedEntries(PackPlayerProfile profile) {
        List<BagEntry> entries = new ArrayList<>();
        for (UUID instanceId : profile.getEquippedPetIds()) {
            profile.findPet(instanceId).ifPresent(pet -> itemRegistry.get().find(pet.getItemId()).ifPresent(item -> {
                Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                long lastObtained = profile.getLastObtainedAt().getOrDefault(pet.getItemId(), 0L);
                entries.add(new BagEntry(pet.getItemId(), item, rarity, 1, lastObtained, pet, equipmentService.effectiveDamage(profile, pet), false));
            }));
        }
        entries.sort(Comparator.comparingDouble(BagEntry::effectiveDamage).reversed());
        return entries;
    }

    /**
     * Baseline (never-progressed) copies of the same item id are grouped
     * into one stack entry showing a count - they're truly interchangeable,
     * so which exact instance equip/fusion/delete touches doesn't matter.
     * The moment a pet has any real progress (leveled, or fed candy), it's
     * shown as its own individual entry with a level badge instead - the
     * only way the Bag stays usable once pets stop all being identical.
     */
    private List<BagEntry> buildStorageEntries(PackPlayerProfile profile) {
        List<BagEntry> entries = new ArrayList<>();
        Map<String, List<PetInstance>> baselineByItemId = new LinkedHashMap<>();
        for (PetInstance pet : profile.getPets()) {
            if (profile.isEquipped(pet.getInstanceId())) {
                continue;
            }
            if (pet.isBaseline()) {
                baselineByItemId.computeIfAbsent(pet.getItemId(), k -> new ArrayList<>()).add(pet);
            } else {
                itemRegistry.get().find(pet.getItemId()).ifPresent(item -> {
                    Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                    long lastObtained = profile.getLastObtainedAt().getOrDefault(pet.getItemId(), 0L);
                    entries.add(new BagEntry(pet.getItemId(), item, rarity, 1, lastObtained, pet, equipmentService.effectiveDamage(profile, pet), false));
                });
            }
        }
        for (var group : baselineByItemId.entrySet()) {
            String itemId = group.getKey();
            List<PetInstance> instances = group.getValue();
            itemRegistry.get().find(itemId).ifPresent(item -> {
                Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
                long lastObtained = profile.getLastObtainedAt().getOrDefault(itemId, 0L);
                PetInstance representative = instances.get(0);
                entries.add(new BagEntry(itemId, item, rarity, instances.size(), lastObtained, representative,
                        equipmentService.effectiveDamage(profile, representative), true));
            });
        }
        return entries;
    }

    private void equip(Player player, UUID instanceId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (equipmentService.manualEquip(profile, instanceId)) {
            player.sendMessage(Text.parse("<green>Equipped.</green>"));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.4f);
            petDisplayService.refresh(player);
            Bukkit.getPluginManager().callEvent(new PetEquippedEvent(player, instanceId));
        } else if (profile.getEquippedPetIds().size() >= equipmentService.getEquipCap(profile)) {
            player.sendMessage(Text.parse("<red>You're at your equip cap (<cap>). Unequip something first.</red>",
                    Placeholder.unparsed("cap", String.valueOf(equipmentService.getEquipCap(profile)))));
        } else {
            player.sendMessage(Text.parse("<red>You don't have any more of this pet to equip.</red>"));
        }
        open(player);
    }

    private void unequip(Player player, UUID instanceId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        equipmentService.manualUnequip(profile, instanceId);
        player.sendMessage(Text.parse("<gray>Unequipped.</gray>"));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
        petDisplayService.refresh(player);
        Bukkit.getPluginManager().callEvent(new PetUnequippedEvent(player, instanceId));
        open(player);
    }

    /**
     * Tries every registered {@link PetItemHandler} (candy, yield-mining's
     * forged held items, etc. - see {@code YieldPacks#applyPetItemHandlers})
     * against this exact pet instance - equipped or bagged, same code path
     * either way. Checks the player's cursor first (drag-and-drop onto the
     * pet's icon - the same gesture Forge/Enchants already teach, and the
     * only thing actually possible while this screen's own item is what's
     * under the mouse) and falls back to whatever's in their main hand
     * (holding the item in their hotbar, then clicking) - either works.
     * Persists and reopens (to show the result) only if one actually
     * applied; returns false to let the click's normal action (equip/
     * unequip/withdraw/delete) proceed untouched otherwise.
     */
    private boolean applyHeldItem(Player player, PetInstance pet, InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && applyHandlers.apply(player, pet, cursor)) {
            event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
            store.save(player.getUniqueId());
            open(player);
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.getType().isAir() && applyHandlers.apply(player, pet, held)) {
            store.save(player.getUniqueId());
            open(player);
            return true;
        }
        return false;
    }

    /** Converts exactly this one instance into a real, physical item (see PetWithdrawItem) - removed from the Bag the instant it becomes an item, same "mutate immediately" idiom every other Bag action already uses. Overflow past a full inventory drops at their feet rather than vanishing. */
    private void withdraw(Player player, BagEntry entry) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        UUID instanceId = entry.representative().getInstanceId();
        PetInstance pet = profile.findPet(instanceId).orElse(null);
        if (pet == null) {
            player.sendMessage(Text.parse("<red>That pet no longer exists.</red>"));
            open(player);
            return;
        }
        profile.removePet(instanceId);
        store.save(player.getUniqueId());
        ItemStack item = withdrawItem.create(entry.item(), pet, entry.rarity(), entry.effectiveDamage());
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(Text.parse("<green>Withdrawn - shift+right-click it to redeem later.</green>"));
        open(player);
    }

    /** Permanently removes exactly this one instance - safe against cutting into an equipped copy since equip/delete always act on a specific instance id, and equipped ones are never shown in storage. */
    private void deleteOne(Player player, UUID instanceId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.removePet(instanceId);
        store.save(player.getUniqueId());
        player.sendMessage(Text.parse("<gray>Deleted 1x.</gray>"));
        open(player);
    }

    private void toggleAttackMode(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAttackMode(profile.getAttackMode() == AttackMode.SINGLE ? AttackMode.ALL : AttackMode.SINGLE);
        store.save(player.getUniqueId());
        open(player);
    }

    private ItemStack buildAttackModeToggle(PackPlayerProfile profile) {
        boolean all = profile.getAttackMode() == AttackMode.ALL;
        ItemBuilder builder = ItemBuilder.of(all ? Material.TNT : Material.ARROW)
                .name(MenuLore.buttonName(ACCENT, all ? "MULTI SEND" : "SINGLE SEND"));
        MenuLore.button(
                "settings",
                List.of(
                        " &7Only used while Auto Mode is off:",
                        " &7Single Send - each click sends one pet.",
                        " &7Multi Send - each click sends the whole squad."
                ),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        if (all) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    private void toggleSendMode(Player player) {
        if (!player.hasPermission(AUTO_MODE_PERMISSION)) {
            player.sendMessage(Text.parse("<red>You don't have permission to use Auto Mode.</red>"));
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAutoAttack(profile.getSendMode() != SendMode.AUTO);
        store.save(player.getUniqueId());
        open(player);
    }

    private ItemStack buildSendModeToggle(Player viewer, PackPlayerProfile profile) {
        if (!viewer.hasPermission(AUTO_MODE_PERMISSION)) {
            ItemBuilder locked = ItemBuilder.of(Material.LEVER).name("&7AUTO SEND");
            MenuLore.info("settings", List.of(" &7Buy &fFree Auto Send&7 from /buy", " &7to unlock this, permanently."), ACCENT, List.of())
                    .forEach(locked::lore);
            return locked.hideAttributes().build();
        }
        boolean auto = profile.getSendMode() == SendMode.AUTO;
        boolean premium = viewer.hasPermission(PREMIUM_AUTO_MODE_PERMISSION);
        ItemBuilder builder = ItemBuilder.of(auto ? Material.CLOCK : Material.LEVER)
                .name(MenuLore.buttonName(ACCENT, "AUTO SEND: " + (auto ? "ON" : "OFF") + (premium ? " &6[Premium]" : "")));
        MenuLore.button(
                "settings",
                !auto
                        ? List.of(" &7Off: no pet fights until you", " &7click a cube - level 10 pets", " &7included.")
                        : premium
                        ? List.of(" &7Pets fight fully hands-off,", " &7re-engaging 2x faster after", " &7each kill (Premium tier).")
                        : List.of(" &7Pets fight fully hands-off,", " &7re-engaging on a cooldown", " &7after each kill. &6/buy&7 Premium", " &7Auto Send to speed that up."),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        if (auto) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    private ItemStack buildDeleteByRarityButton() {
        ItemBuilder builder = ItemBuilder.of(Material.LAVA_BUCKET).name(MenuLore.buttonName(ACCENT, "DELETE BY RARITY"));
        MenuLore.button(
                "cleanup",
                List.of(" &7Bulk-delete every unequipped", " &7pet of a chosen rarity at once."),
                ACCENT,
                "Click to Open"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildEquipBestButton() {
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.buttonName("<green>", "EQUIP BEST"));
        MenuLore.button(
                "utility",
                List.of(" &7Equips your best,", " &7pets based on boost."),
                "<green>",
                "Click to Equip Best"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private void equipBest(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        equipmentService.equipBest(profile);
        store.save(player.getUniqueId());
        player.sendMessage(Text.parse("<green>Equipped your best pets.</green>"));
        open(player);
    }

    /** This item's fusion tier tag (if any) + its plain name + a "[Lv. N]" bracket - never a gradiented name, see FusionTier#tag. A Huge pet's own display-name is expected to already say "Huge" (e.g. "Huge Diamond Wolf") - that's the only indicator it works differently, not a separately-injected badge here. */
    private String buildNameLine(BagEntry entry, boolean equipped) {
        ItemDefinition item = entry.item();
        String tag = PetLabels.tagsFor(item, entry.representative());
        String prefix = tag != null ? tag + " " : "";
        String levelBracket = " &8[&7Lv. " + entry.representative().getLevel() + "&8]";
        String equippedSuffix = equipped ? " &a[Equipped]" : "";
        return prefix + item.displayName() + levelBracket + equippedSuffix;
    }

    /** Display order for a pet's permanent forge bonuses (see {@code PetInstance#getForgeBonuses}) - fixed rather than map iteration order, so the lore doesn't reshuffle from one render to the next. Raw string keys, not yield-mining's own {@code ForgeStatType} enum - yield-packs can't depend on yield-mining (wrong dependency direction). */
    private static final List<String> FORGE_STAT_ORDER = List.of("DAMAGE", "COINS", "DIAMONDS", "ATTACK_SPEED", "LUCK");

    private ItemStack buildIcon(BagEntry entry, boolean equipped) {
        ItemDefinition item = entry.item();
        PetInstance pet = entry.representative();
        String accent = entry.rarity() != null ? "<" + entry.rarity().colorHex() + ">" : "<#FFFFFF>";

        List<String> data = new ArrayList<>();
        data.add("&7Rarity: " + (entry.rarity() != null ? entry.rarity().displayName() : "&7Unknown"));
        if (item.huge()) {
            data.add("&7Damage: &4❤&c" + Formatting.format(entry.effectiveDamage())
                    + " &7(" + Formatting.format(item.hugeDamagePercent() * 100) + "% of best pet)");
        } else {
            data.add("&7Damage: &4❤&c" + Formatting.format(entry.effectiveDamage()));
        }
        if (entry.stack()) {
            data.add("&7Owned: &f" + entry.count());
        } else {
            data.add("&7Level: &f" + pet.getLevel());
            data.add(petLevelingService.buildXpLine(pet));
        }
        for (String statKey : FORGE_STAT_ORDER) {
            double bonus = pet.getForgeBonuses().getOrDefault(statKey, 0.0);
            if (bonus != 0.0) {
                data.add("&7Forged " + prettyForgeStat(statKey) + ": &a+"
                        + String.format(Locale.ROOT, "%.1f", bonus * 100) + "%");
            }
        }
        PetEnchantLore.appendEnchantLines(data, pet, petEnchantContent.get());

        List<String> lore = new ArrayList<>(MenuLore.info("pet", List.of(), accent, data));
        if (item.trackExists()) {
            lore.add("");
            lore.add("&7" + Formatting.format(existsCounterStore.get(item.id())) + " Exist");
        }
        lore.add("");
        lore.add("&7" + (equipped ? "Left-Click to Unequip" : "Left-Click to Equip"));
        if (!equipped) {
            lore.add("&7Right-Click to Withdraw as an Item");
            lore.add("&7Shift+Right-Click to Delete");
        }

        ItemBuilder builder = iconFactory.baseIcon(item).name(buildNameLine(entry, equipped));
        builder.lore(lore);
        return builder
                .amount(equipped ? 1 : Math.max(1, Math.min(64, entry.count())))
                .hideAttributes()
                .build();
    }

    /** "ATTACK_SPEED" -> "Attack Speed" - {@link #FORGE_STAT_ORDER}'s raw keys are yield-mining's ForgeStatType#name(), which yield-packs can't reference directly. */
    private static String prettyForgeStat(String rawKey) {
        String[] words = rawKey.split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
