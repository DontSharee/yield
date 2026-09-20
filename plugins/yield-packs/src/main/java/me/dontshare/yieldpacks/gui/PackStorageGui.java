package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.dialog.OpenPackDialog;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackRollService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A grid of unopened pack stacks - clicking one selects it as this player's
 * active pack (for both manual and auto opening) and opens {@link
 * OpenPackDialog}. Purely a storage/selection screen - no rolling happens
 * from here directly. Only the top/bottom rows are filler; slot 4 always
 * shows whichever pack is currently selected (matching the physical Pack
 * Selector item's own notion of "selected"), and the bottom row has a
 * shortcut straight into the shop.
 */
public final class PackStorageGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 45; // exclusive
    private static final int SELECTED_SLOT = 4;
    private static final int MERCHANT_SLOT = 45;
    private static final int AUTO_OPEN_SLOT = 47;
    private static final int ANIMATION_SLOT = 48;
    private static final int CLOSE_SLOT = 49;

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final GuiManager guiManager;
    private final OpenPackDialog openPackDialog;
    private final PackShopGui packShopGui;
    private final PackRollService rollService;
    private final PackOddsLore oddsLore;

    public PackStorageGui(Supplier<PackContentLoader.ContentSnapshot> content, PlayerDataStore<PackPlayerProfile> playerStore,
                           GuiManager guiManager, OpenPackDialog openPackDialog, PackShopGui packShopGui,
                           PackRollService rollService, PackOddsLore oddsLore) {
        this.content = content;
        this.playerStore = playerStore;
        this.guiManager = guiManager;
        this.openPackDialog = openPackDialog;
        this.packShopGui = packShopGui;
        this.rollService = rollService;
        this.oddsLore = oddsLore;
    }

    public void open(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        List<PackDefinition> stored = content.get().packs().all().stream()
                .filter(pack -> profile.getStoredPacks().getOrDefault(pack.id(), 0) > 0)
                .toList();

        var builder = Gui.builder(TOTAL_ROWS, "Pack Storage");

        // Row 0: border, except the selected-pack widget in the middle.
        builder.fill(IntStream.range(0, 9).filter(i -> i != SELECTED_SLOT), GuiIcons.filler());
        buildSelectedWidget(profile).ifPresentOrElse(
                widget -> builder.item(SELECTED_SLOT, widget,
                        (clicker, event) -> content.get().packs().find(profile.getActivePackId())
                                .ifPresent(pack -> openPackDialog.open(clicker, pack))),
                () -> builder.item(SELECTED_SLOT, buildNoneSelectedIcon()));

        // Rows 1-4: pack stacks, starting at slot 9 - unused cells stay empty, matching BagGui's convention.
        int slot = CONTENT_START;
        for (PackDefinition pack : stored) {
            if (slot >= CONTENT_END) {
                break;
            }
            int count = profile.getStoredPacks().getOrDefault(pack.id(), 0);
            builder.item(slot, buildIcon(pack, count, player), (clicker, event) -> openPackDialog.open(clicker, pack));
            slot++;
        }

        // Row 5: border, plus the close button, a shortcut to the shop, and
        // the two top-level toggles (auto-open, roll animation) - neither
        // requires picking a specific pack first, unlike the per-pack
        // auto-open toggle tucked inside OpenPackDialog.
        builder.fill(IntStream.range(CONTENT_END, TOTAL_ROWS * 9)
                .filter(i -> i != MERCHANT_SLOT && i != AUTO_OPEN_SLOT && i != ANIMATION_SLOT && i != CLOSE_SLOT),
                GuiIcons.filler());
        builder.item(MERCHANT_SLOT, buildMerchantIcon(), (clicker, event) -> packShopGui.open(clicker));
        builder.item(AUTO_OPEN_SLOT, buildAutoOpenIcon(profile), (clicker, event) -> toggleAutoOpen(clicker));
        builder.item(ANIMATION_SLOT, buildAnimationIcon(profile), (clicker, event) -> toggleAnimation(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private Optional<ItemStack> buildSelectedWidget(PackPlayerProfile profile) {
        String activeId = profile.getActivePackId();
        if (activeId == null) {
            return Optional.empty();
        }
        int count = profile.getStoredPacks().getOrDefault(activeId, 0);
        if (count <= 0) {
            return Optional.empty();
        }
        return content.get().packs().find(activeId).map(pack -> {
            ItemBuilder builder = ItemBuilder.of(pack.material())
                    .name(ACCENT + "&l[Selected] " + pack.displayName());
            if (pack.customModelData() != null) {
                builder.modelData(pack.customModelData());
            }
            MenuLore.info("pack storage", List.of(" &7The pack your Pack Selector", " &7item currently opens."), ACCENT,
                    List.of("&7Stored: &f" + count)).forEach(builder::lore);
            return builder.amount(Math.max(1, Math.min(64, count))).hideAttributes().build();
        });
    }

    private ItemStack buildNoneSelectedIcon() {
        return ItemBuilder.of(Material.GRAY_DYE)
                .name("&7No Pack Selected")
                .lore("&7Click a pack below, or use")
                .lore("&7your Pack Selector item.")
                .hideAttributes()
                .build();
    }

    private ItemStack buildMerchantIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.buttonName(ACCENT, "MERCHANT"));
        MenuLore.button(
                "navigation",
                List.of(" &7Jump straight to", " &fthe shop&7 to buy more."),
                ACCENT,
                "Click to Open Shop"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * A top-level auto-open toggle that doesn't require a pack already
     * selected - turning it on with nothing selected (or the selected pack
     * empty) auto-picks whichever stored pack is worth the most (see
     * {@link PackRollService#bestStoredPackId}), then keeps cascading
     * through the rest of storage best-to-worst as each one runs out (see
     * {@code PackRollService#advanceActivePackIfExhausted}) - "auto-open
     * everything," not "auto-open one pack you had to pick first."
     */
    private ItemStack buildAutoOpenIcon(PackPlayerProfile profile) {
        boolean on = profile.isAutoOpenEnabled();
        ItemBuilder builder = ItemBuilder.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, "AUTO-OPEN: " + (on ? "&aON" : "&cOFF")));
        MenuLore.button(
                "pack storage",
                List.of(" &7Automatically opens whatever's", " &7stored, best pack first, until", " &7you're out - no need to pick one."),
                ACCENT,
                on ? "Click to Disable" : "Click to Enable"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** Toggles the SAME setting as {@code /rollanimation} - when off, opening still shows the action-bar cycling reveal (odds, pity bar), just without the in-world item strip in front of the player (see PackRevealAnimationService#playCompactReel). */
    private ItemStack buildAnimationIcon(PackPlayerProfile profile) {
        boolean on = profile.isRollAnimationEnabled();
        ItemBuilder builder = ItemBuilder.of(on ? Material.FIREWORK_ROCKET : Material.PAPER)
                .name(MenuLore.buttonName(ACCENT, "ROLL ANIMATION: " + (on ? "&aON" : "&cOFF")));
        MenuLore.button(
                "pack storage",
                List.of(" &7Toggles the in-world pack-opening", " &7reveal. The action-bar cycling", " &7and odds still show either way."),
                ACCENT,
                on ? "Click to Disable" : "Click to Enable"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * Enabling works even with empty storage - {@code PackOpenService
     * #autoOpenTick} re-derives whichever pack is worth the most every
     * tick it finds nothing currently active/stocked, so this stays ON and
     * silently starts opening the instant this player acquires ANY pack,
     * with no need to come back and re-toggle it.
     */
    private void toggleAutoOpen(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        boolean enabling = !profile.isAutoOpenEnabled();
        if (enabling) {
            String activeId = profile.getActivePackId();
            boolean activeHasStock = activeId != null && profile.getStoredPacks().getOrDefault(activeId, 0) > 0;
            if (!activeHasStock) {
                String best = rollService.bestStoredPackId(profile);
                if (best != null) {
                    profile.setActivePackId(best);
                }
            }
        }
        profile.setAutoOpenEnabled(enabling);
        playerStore.save(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, enabling ? 1.4f : 1f);
        player.sendMessage(Text.parse(enabling
                ? "<green>Auto-open enabled - opens whatever you have stored, best first, and picks up anything new automatically.</green>"
                : "<gray>Auto-open disabled.</gray>"));
        open(player);
    }

    private void toggleAnimation(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        boolean enabling = !profile.isRollAnimationEnabled();
        profile.setRollAnimationEnabled(enabling);
        playerStore.save(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, enabling ? 1.4f : 1f);
        open(player);
    }

    private ItemStack buildIcon(PackDefinition pack, int count, Player viewer) {
        ItemBuilder builder = ItemBuilder.of(pack.material()).name(MenuLore.buttonName(ACCENT, pack.displayName()));
        if (pack.customModelData() != null) {
            builder.modelData(pack.customModelData());
        }
        MenuLore.button(
                "pack storage",
                List.of(" &7Select this pack to", " &fopen&7 it, one at a time,", " &7or toggle &fauto-open&7."),
                ACCENT,
                "Click to Manage"
        ).forEach(builder::lore);
        builder.lore("");
        // The same odds block the shop shows, on the screen where a player
        // actually decides which of their packs to open - the two used to
        // disagree, the shop listing raw weights and this showing nothing
        // at all.
        oddsLore.lines(pack, viewer).forEach(builder::lore);
        return builder
                .lore("")
                .lore("&7Stored: &f" + count)
                .amount(Math.max(1, Math.min(64, count)))
                .hideAttributes()
                .build();
    }
}
