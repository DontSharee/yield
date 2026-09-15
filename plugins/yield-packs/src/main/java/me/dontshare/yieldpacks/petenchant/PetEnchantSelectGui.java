package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
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
 * The pet picker opened from {@link PetEnchantTableGui}'s own center slot -
 * every owned pet, equipped ones included, shown individually (never
 * stacked - unlike {@code BagGui}'s storage grid, an enchant roll is
 * inherently per-instance, so which exact copy gets picked always matters
 * here). Selecting one hands it to {@link PetEnchantService#select}
 * (anti-dupe unequip included) and returns to the table.
 */
public final class PetEnchantSelectGui {

    private static final int TOTAL_ROWS = 6;
    private static final int GRID_SIZE = 36; // rows 2-5, slots 9-44
    private static final int BACK_SLOT = 49;
    private static final String ACCENT = MenuLore.ACCENT;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final EquipmentService equipmentService;
    private final ItemIconFactory iconFactory;
    private final PetEnchantService enchantService;
    private final Supplier<PetEnchantContent> content;
    private final GuiManager guiManager;
    private PetEnchantTableGui tableGui;

    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public PetEnchantSelectGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                                Supplier<RarityRegistry> rarityRegistry, EquipmentService equipmentService,
                                ItemIconFactory iconFactory, PetEnchantService enchantService,
                                Supplier<PetEnchantContent> content, GuiManager guiManager) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.equipmentService = equipmentService;
        this.iconFactory = iconFactory;
        this.enchantService = enchantService;
        this.content = content;
        this.guiManager = guiManager;
    }

    /** Set once, right after both GUIs exist - see {@code YieldPacks#onEnable}'s own {@code setPackStorageGui} precedent for this exact circular-reference pattern. */
    public void setTableGui(PetEnchantTableGui tableGui) {
        this.tableGui = tableGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<PetInstance> pets = new ArrayList<>(profile.getPets());
        pets.sort(Comparator.comparingDouble((PetInstance pet) -> equipmentService.effectiveDamage(profile, pet)).reversed());
        Page<PetInstance> page = Page.of(pets, pageIndex.getOrDefault(player.getUniqueId(), 0), GRID_SIZE);
        pageIndex.put(player.getUniqueId(), page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Select a Pet to Enchant");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());

        List<PetInstance> items = page.items();
        for (int i = 0; i < items.size(); i++) {
            PetInstance pet = items.get(i);
            builder.item(9 + i, buildIcon(profile, pet), (clicker, e) -> {
                PackPlayerProfile clickerProfile = store.getOrCreate(clicker.getUniqueId());
                enchantService.select(clicker, clickerProfile, pet.getInstanceId());
                store.save(clicker.getUniqueId());
                tableGui.open(clicker);
            });
        }

        builder.item(45, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, page, -1));
        builder.item(53, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, page, 1));
        builder.item(BACK_SLOT, buildBackButton(), (clicker, e) -> tableGui.open(clicker));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, Page<PetInstance> currentPage, int delta) {
        boolean canMove = delta < 0 ? currentPage.hasPrevious() : currentPage.hasNext();
        if (!canMove) {
            return;
        }
        pageIndex.put(player.getUniqueId(), currentPage.index() + delta);
        open(player);
    }

    private ItemStack buildIcon(PackPlayerProfile profile, PetInstance pet) {
        ItemDefinition item = itemRegistry.get().find(pet.getItemId()).orElse(null);
        if (item == null) {
            return GuiIcons.filler();
        }
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String accent = rarity != null ? "<" + rarity.colorHex() + ">" : "<#FFFFFF>";
        boolean equipped = profile.isEquipped(pet.getInstanceId());

        List<String> data = new ArrayList<>();
        data.add("&7Rarity: " + (rarity != null ? rarity.displayName() : "&7Unknown"));
        data.add("&7Damage: &4❤&c" + Formatting.format(equipmentService.effectiveDamage(profile, pet)));
        data.add("&7Level: &f" + pet.getLevel());
        if (equipped) {
            data.add("&a✓ Currently Equipped");
        }
        PetEnchantLore.appendEnchantLines(data, pet, content.get());

        ItemBuilder builder = iconFactory.baseIcon(item)
                .name(MenuLore.buttonName(accent, Formatting.stripLeadingColorCodes(item.displayName()).toUpperCase(java.util.Locale.ROOT)));
        MenuLore.button("select pet", List.of(), accent, data, "Click to Select").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(org.bukkit.Material.ARROW).name(MenuLore.buttonName(ACCENT, "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the Enchanting Table"), ACCENT, "Click to Go Back").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
