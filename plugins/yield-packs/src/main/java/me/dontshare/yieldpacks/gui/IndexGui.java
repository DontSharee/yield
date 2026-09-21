package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The Index - every pet in the game, which ones you own, and how many.
 * <p>
 * It used to list the EGGS and their completion counts ("7/10 from this
 * egg"), which answered a question nobody was asking: a collection screen
 * exists so a player can look at the pets. Finding out whether you have the
 * Sovereign Stag meant opening the right egg's entry and counting. So this
 * is a flat list of pets now, weakest first, with the ones you have in
 * colour and the ones you don't greyed out but still named - the same shape
 * as the Huge Index, which was already doing it the useful way.
 * <p>
 * Fusion tiers and Huges are deliberately left out: a Golden Stray Cat is
 * the same entry as a Stray Cat as far as "have I seen this pet" goes, and
 * Huges have their own screen because they are their own chase.
 * <p>
 * The per-egg completion bonus still exists (see {@link LuckService}) and
 * is what the header's luck figure reports - only the way of showing it
 * changed.
 */
public final class IndexGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int CONTENT_SLOTS = 45;
    private static final int PREV_SLOT = 45;
    private static final int HUGE_SLOT = 47;
    private static final int HEADER_SLOT = 49;
    private static final int CLOSE_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final LuckService luckService;
    private final GuiManager guiManager;
    private final ItemIconFactory iconFactory;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    /** Set after construction - the two screens point at each other, so one of them has to be wired second. */
    private HugeIndexGui hugeIndexGui;

    public IndexGui(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                     LuckService luckService, GuiManager guiManager, ItemIconFactory iconFactory) {
        this.content = content;
        this.store = store;
        this.luckService = luckService;
        this.guiManager = guiManager;
        this.iconFactory = iconFactory;
    }

    public void setHugeIndexGui(HugeIndexGui hugeIndexGui) {
        this.hugeIndexGui = hugeIndexGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<ItemDefinition> pets = allPets();
        Map<String, Integer> owned = ownedCounts(profile);
        double luckPercent = (luckService.totalLuckMultiplier(profile) - 1.0) * 100;

        Page<ItemDefinition> page = Page.of(pets, pageIndex.getOrDefault(player.getUniqueId(), 0), CONTENT_SLOTS);
        GuiBuilder builder = Gui.builder(6, "Index (+" + Math.round(luckPercent) + "% luck)");
        int slot = 0;
        for (ItemDefinition pet : page.items()) {
            builder.item(slot++, buildIcon(pet, owned.getOrDefault(pet.id(), 0)));
        }
        builder.fill(IntStream.range(slot, CONTENT_SLOTS), GuiIcons.filler());
        builder.fill(IntStream.range(CONTENT_SLOTS, 54)
                .filter(s -> s != PREV_SLOT && s != HUGE_SLOT && s != HEADER_SLOT
                        && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());

        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        // Huges are never in an egg's pool, so they cannot appear on this
        // screen at all - this is the only way in to seeing them.
        if (hugeIndexGui != null) {
            builder.item(HUGE_SLOT, hugeIndexButton(), (clicker, event) -> hugeIndexGui.open(clicker));
        }
        builder.item(HEADER_SLOT, buildHeaderIcon(pets, owned));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));
        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        int next = Math.max(0, pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        pageIndex.put(player.getUniqueId(), next);
        open(player);
    }

    /** Every base pet, weakest first - which is roughly the order a player meets them, so their own progress reads top-left to bottom-right. */
    private List<ItemDefinition> allPets() {
        var rarities = content.get().rarities();
        List<ItemDefinition> pets = new ArrayList<>(content.get().items().all().stream()
                .filter(item -> item.fusionTier() == FusionTier.NORMAL)
                .filter(item -> !item.huge())
                .toList());
        pets.sort(Comparator
                .comparingInt((ItemDefinition item) -> rarities.find(item.rarityId()).map(Rarity::sortOrder).orElse(0))
                .thenComparingDouble(ItemDefinition::damage)
                .thenComparing(ItemDefinition::displayName));
        return pets;
    }

    private Map<String, Integer> ownedCounts(PackPlayerProfile profile) {
        Map<String, Integer> counts = new HashMap<>();
        for (PetInstance pet : profile.getPets()) {
            counts.merge(pet.getItemId(), 1, Integer::sum);
        }
        return counts;
    }

    private ItemStack buildHeaderIcon(List<ItemDefinition> pets, Map<String, Integer> owned) {
        long found = pets.stream().filter(pet -> owned.getOrDefault(pet.id(), 0) > 0).count();
        ItemBuilder builder = ItemBuilder.of(Material.BOOK).name(MenuLore.infoName(ACCENT, "INDEX"));
        MenuLore.info("index",
                List.of(" &7Every pet in the game, and", " &7which ones you've found.",
                        " &7Collect a whole egg's pets", " &7for &a+5% &7luck."),
                ACCENT,
                List.of("Found: &f" + found + "/" + pets.size())
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildIcon(ItemDefinition pet, int ownedCount) {
        Rarity rarity = content.get().rarities().find(pet.rarityId()).orElse(null);
        String color = rarity != null ? "<" + rarity.colorHex() + ">" : "<white>";
        boolean found = ownedCount > 0;
        // An unfound pet still shows its real name and rarity rather than a
        // "???" - the point of the screen is to make someone want it, which
        // means letting them see what it is.
        ItemBuilder builder = found
                ? iconFactory.baseIcon(pet).name(color + Formatting.stripLeadingColorCodes(pet.displayName()))
                : ItemBuilder.of(Material.GRAY_DYE).name("<dark_gray>" + Formatting.stripLeadingColorCodes(pet.displayName()));
        List<String> data = new ArrayList<>();
        data.add(rarity != null ? rarity.displayName() : "");
        data.add("Damage: &f" + Formatting.format(pet.damage()));
        data.add(found ? "Owned: &f" + ownedCount : "&8Not found yet");
        MenuLore.info("pet", List.of(), found ? color : "<dark_gray>", data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack hugeIndexButton() {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(MenuLore.buttonName("<#FFD700>", "HUGE INDEX"));
        MenuLore.button("huge index",
                List.of(" &7Every &6Huge&7 in the game,", " &7and which ones you've found."),
                "<#FFD700>", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
