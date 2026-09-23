package me.dontshare.yieldpacks.enchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * "/enchants" - 9 fixed slots (index 0-8, filling the entire top row),
 * drag-and-drop only - matches PS99's own Enchantment Slots layout: 6
 * free (unlocked progressively) + 3 premium (donor-rank-gated), see
 * {@link EnchantService#totalSlots}. Unlocked slots are real editable
 * slots; the rest render locked. The player's own {@code
 * PackPlayerProfile.getEnchantSlots()} is the durable source of truth -
 * this GUI just regenerates the real display item for each filled slot
 * on open, and writes back whatever's actually sitting in each slot once
 * a drag/click resolves, same round-trip idiom {@code OreBagGui}/{@code
 * OreBagService} already established.
 */
public final class EnchantGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int[] SLOT_POSITIONS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    /** Info and the market as a pair either side of the middle column; Close centred underneath. */
    private static final int INFO_SLOT = 21;
    private static final int MARKET_SLOT = 23;
    private static final int CLOSE_SLOT = 31;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final EnchantService service;
    private final EnchantItem enchantItem;
    private final Supplier<RarityRegistry> rarities;
    private final GuiManager guiManager;
    /** Set after construction - the market screen and this one point at each other. */
    private EnchantMarketGui marketGui;

    public EnchantGui(PlayerDataStore<PackPlayerProfile> store, EnchantService service, EnchantItem enchantItem,
                       Supplier<RarityRegistry> rarities, GuiManager guiManager) {
        this.store = store;
        this.service = service;
        this.enchantItem = enchantItem;
        this.rarities = rarities;
        this.guiManager = guiManager;
    }

    public void setMarketGui(EnchantMarketGui marketGui) {
        this.marketGui = marketGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int unlocked = service.totalSlots(profile);
        List<String> slots = profile.getEnchantSlots();

        var builder = Gui.builder(4, "Enchants");
        builder.fill(IntStream.range(0, 36), GuiIcons.filler());

        IntStream.range(0, EnchantService.SLOT_COUNT).forEach(i -> {
            int pos = SLOT_POSITIONS[i];
            if (i < unlocked) {
                builder.item(pos, decodeIcon(slots.get(i)));
            } else {
                builder.item(pos, lockedIcon(profile, i));
            }
        });
        builder.editableSlots(IntStream.range(0, unlocked).map(i -> SLOT_POSITIONS[i]));

        // The slots hold books regenerated from the profile, so the profile
        // has to be reconciled against them before this screen can go away -
        // by EITHER route out, whichever happens first. Reconciling only from
        // the deferred slot-change callback was a duplication bug: taking a
        // book out and closing in the same tick meant the callback found the
        // screen gone and returned, leaving the book still recorded in the
        // profile while the real one sat in the player's inventory, ready to
        // be regenerated on the next open.
        Gui[] self = new Gui[1];
        boolean[] reconciled = {false};

        builder.onEditableSlotChange(clicker -> {
            // Identity, not "some Gui": a deferred callback landing after the
            // player moved to another screen would otherwise read THAT
            // screen's slots by raw index and act on them.
            if (reconciled[0] || clicker.getOpenInventory().getTopInventory().getHolder() != self[0]) {
                return;
            }
            reconciled[0] = true;
            reconcile(clicker, self[0]);
            open(clicker);
        });

        builder.item(INFO_SLOT, buildSummaryIcon(profile));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        // Leaving for the market goes through the close handler below like
        // any other exit, so whatever is in the slots is saved first.
        if (marketGui != null) {
            builder.item(MARKET_SLOT, marketIcon(), (clicker, e) -> marketGui.open(clicker));
        }

        Gui gui = builder.build();
        self[0] = gui;
        gui.setCloseHandler(clicker -> {
            if (reconciled[0]) {
                return;
            }
            reconciled[0] = true;
            reconcile(clicker, gui);
        });
        guiManager.open(player, gui);
    }

    /** Reads back whatever's actually sitting in each unlocked slot, re-encodes it onto the profile, and bounces anything that isn't a real enchant book. */
    private void reconcile(Player player, Gui gui) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int unlocked = service.totalSlots(profile);
        Inventory top = gui.getInventory();
        List<String> slots = profile.getEnchantSlots();

        for (int i = 0; i < unlocked; i++) {
            int pos = SLOT_POSITIONS[i];
            ItemStack current = top.getItem(pos);
            if (current == null || current.getType().isAir()) {
                slots.set(i, "");
            } else if (enchantItem.isEnchantBook(current)) {
                slots.set(i, enchantItem.typeOf(current).name() + ":" + enchantItem.rarityIdOf(current));
            } else {
                // Not a real Enchant Book - bounce it back rather than letting it sit in the slot.
                top.setItem(pos, null);
                giveOrDrop(player, current);
                slots.set(i, "");
            }
        }
        store.save(player.getUniqueId());
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private ItemStack decodeIcon(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        EnchantType type;
        try {
            type = EnchantType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Rarity rarity = rarities.get().find(parts[1]).orElse(null);
        if (rarity == null) {
            return null;
        }
        return enchantItem.create(type, rarity);
    }

    private ItemStack lockedIcon(PackPlayerProfile profile, int slotIndex) {
        String unlockHint = service.isPremiumSlot(slotIndex)
                ? "Purchase a donor rank at /buy"
                : "Reach " + service.rebirthsNeededFor(profile, slotIndex) + " more rebirth(s)";
        ItemBuilder builder = ItemBuilder.of(Material.BARRIER).name("&7&lLOCKED SLOT");
        MenuLore.info("enchants", List.of(" &7" + unlockHint + " &7to", " &7unlock this slot."), ACCENT, List.of())
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack marketIcon() {
        long minutes = Math.max(1, (EnchantMarketService.millisUntilRestock() + 59_999) / 60_000);
        ItemBuilder builder = ItemBuilder.of(Material.CLOCK).name(MenuLore.buttonName(ACCENT, "ENCHANT MARKET"));
        MenuLore.button("market", List.of(" &7New books every hour.", " &7Restocks in &f" + minutes + "m&7."),
                ACCENT, "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildSummaryIcon(PackPlayerProfile profile) {
        ItemBuilder builder = ItemBuilder.of(Material.ENCHANTED_BOOK).name(MenuLore.infoName(ACCENT, "ACTIVE BONUSES"));
        List<String> data = new ArrayList<>();
        for (EnchantType type : EnchantType.values()) {
            // LUCK's own registry is additive (no +1.0 baseline) - every other type's is a multiplier.
            double total = type == EnchantType.LUCK
                    ? service.additiveFor(type).apply(profile)
                    : service.multiplierFor(type).apply(profile) - 1.0;
            data.add(type.displayName() + ": &a+" + String.format(Locale.ROOT, "%.1f", total * 100) + "%");
        }
        MenuLore.info("enchants", List.of(" &7Drag books from your inventory", " &7into an open slot above.",
                        " &7Books drop rarely from cubes,", " &7or buy them at the market."), ACCENT, data)
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
