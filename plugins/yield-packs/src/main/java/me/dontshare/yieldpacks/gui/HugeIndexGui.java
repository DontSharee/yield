package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.Rarity;
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
 * The Huge showcase - every Huge that exists in the game, which ones you
 * own, and how many.
 * <p>
 * This is the shop window for the chase. Huges are never in any pack's
 * {@code pool:} (they replace a roll rather than being rolled - see
 * {@code PackRollService}), which means the normal Index, organised by pack
 * pools, cannot show them at all. Without this screen a player has no way
 * to learn that Huges exist, what they look like, or how close they are to
 * a complete set - the rarest content in the game would be invisible.
 * <p>
 * Ownership is read from the player's actual pets rather than from
 * collection progress, because collection progress is keyed per pack and a
 * Huge can arrive from any pack containing its base pet.
 */
public final class HugeIndexGui {

    private static final String ACCENT = "<#FFD700>";
    private static final int CONTENT_SLOTS = 45;
    /** Four centred rows of seven under the header - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(4);
    private static final int PREV_SLOT = 47;
    /** Top-centre, above the grid - the bottom bar is just the arrows and Close. */
    private static final int HEADER_SLOT = 4;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final ItemIconFactory iconFactory;
    private final GuiManager guiManager;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public HugeIndexGui(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> store,
                         ItemIconFactory iconFactory, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.iconFactory = iconFactory;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<ItemDefinition> huges = allHuges();
        Map<String, Integer> owned = ownedCounts(profile);

        Page<ItemDefinition> page = Page.of(huges, pageIndex.getOrDefault(player.getUniqueId(), 0), PAGE_SIZE);
        GuiBuilder builder = Gui.builder(6, "Huge Index");
        builder.fill(IntStream.range(0, CONTENT_SLOTS), GuiIcons.filler());
        int[] contentSlots = GuiLayout.centered(1, page.items().size());
        int slot = 0;
        for (ItemDefinition huge : page.items()) {
            builder.item(contentSlots[slot++], buildIcon(huge, owned.getOrDefault(huge.id(), 0)));
        }
        builder.fill(IntStream.range(CONTENT_SLOTS, 54)
                .filter(s -> s != PREV_SLOT && s != HEADER_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        builder.item(HEADER_SLOT, buildHeaderIcon(huges, owned));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));
        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        int next = Math.max(0, pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        pageIndex.put(player.getUniqueId(), next);
        open(player);
    }

    /** Every Huge in the game, rarest first so the hardest ones lead - within a rarity, alphabetical for a stable order. */
    private List<ItemDefinition> allHuges() {
        var rarities = content.get().rarities();
        List<ItemDefinition> huges = new ArrayList<>(content.get().items().all().stream()
                .filter(ItemDefinition::huge)
                .toList());
        huges.sort(Comparator
                .comparingInt((ItemDefinition item) -> rarities.find(item.rarityId()).map(Rarity::sortOrder).orElse(0))
                .reversed()
                .thenComparing(ItemDefinition::displayName));
        return huges;
    }

    private Map<String, Integer> ownedCounts(PackPlayerProfile profile) {
        Map<String, Integer> counts = new HashMap<>();
        for (PetInstance pet : profile.getPets()) {
            counts.merge(pet.getItemId(), 1, Integer::sum);
        }
        return counts;
    }

    private ItemStack buildHeaderIcon(List<ItemDefinition> huges, Map<String, Integer> owned) {
        long found = huges.stream().filter(huge -> owned.getOrDefault(huge.id(), 0) > 0).count();
        double chance = content.get().variants().hugeChance();
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.infoName(ACCENT, "HUGE INDEX"));
        MenuLore.info("huge index",
                List.of(" &7Huges replace a normal roll", " &7from &fany pack&7, at random.",
                        " &7Their damage scales off your", " &7best pet, so they never", " &7go out of date."),
                ACCENT,
                List.of("Found: " + MenuLore.progress(found, huges.size()),
                        "Base odds: &f1 in " + Formatting.format(chance > 0 ? Math.round(1.0 / chance) : 0))
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildIcon(ItemDefinition huge, int ownedCount) {
        Rarity rarity = content.get().rarities().find(huge.rarityId()).orElse(null);
        String color = rarity != null ? "<" + rarity.colorHex() + ">" : "<white>";
        boolean found = ownedCount > 0;
        // An unfound Huge deliberately still shows its real name and rarity
        // rather than being hidden behind a "???" - the point of this screen
        // is to make people want one, which means letting them see it.
        ItemBuilder builder = found
                ? iconFactory.baseIcon(huge).name(color + Formatting.stripLeadingColorCodes(huge.displayName()))
                : ItemBuilder.of(Material.GRAY_DYE).name("<dark_gray>" + Formatting.stripLeadingColorCodes(huge.displayName()));
        List<String> data = new ArrayList<>();
        data.add(rarity != null ? rarity.displayName() : "");
        data.add("Power: &f+" + Math.round(huge.hugeDamagePercent() * 100) + "% &7of your best pet");
        data.add(found ? "Owned: &f" + ownedCount : "&8Not found yet");
        MenuLore.info("huge", List.of(), found ? color : "<dark_gray>", data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
