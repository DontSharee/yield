package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackOpenService;
import me.dontshare.yieldpacks.roll.PackRollService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * What a player sees when they right-click an egg: everything that can come
 * out of it, and how many to hatch at once.
 * <p>
 * The drop list is the point. It is the same luck-adjusted odds the shop
 * and the catalog show (see {@link PackOddsLore}), but laid out one pet per
 * slot with its own icon, sorted rarest-last, so the thing a player is
 * chasing is a thing they can look at before they spend. The tier buttons
 * along the bottom are {@link PackOpenService#HATCH_TIERS}; a rung the
 * player cannot afford, or has not bought the gamepass for, is shown as a
 * barrier that says which of the two it is rather than being hidden.
 */
public final class HatchMenuGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int DROP_SLOTS = 36;
    private static final int TIER_ROW = 45;
    private static final int AUTO_SLOT = 50;
    private static final int CLOSE_SLOT = 53;

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final GuiManager guiManager;
    private final PackOddsLore oddsLore;
    private final PackOpenService openService;
    private final PackRollService rollService;
    private final ItemIconFactory iconFactory;
    private final PlayerDataStore<PackPlayerProfile> store;

    public HatchMenuGui(Supplier<PackContentLoader.ContentSnapshot> content, GuiManager guiManager,
                         PackOddsLore oddsLore, PackOpenService openService, PackRollService rollService,
                         ItemIconFactory iconFactory, PlayerDataStore<PackPlayerProfile> store) {
        this.content = content;
        this.guiManager = guiManager;
        this.oddsLore = oddsLore;
        this.openService = openService;
        this.rollService = rollService;
        this.iconFactory = iconFactory;
        this.store = store;
    }

    public void open(Player player, String packId) {
        PackDefinition egg = content.get().packs().find(packId).orElse(null);
        if (egg == null) {
            player.sendMessage(Text.parse("<red>That egg no longer exists.</red>"));
            return;
        }

        GuiBuilder builder = Gui.builder(6, Formatting.stripLeadingColorCodes(egg.displayName()));
        List<PackRollService.WeightedOdds> drops = new ArrayList<>(
                rollService.oddsFor(egg, rollService.displayLuckFor(player)));
        drops.sort(Comparator.comparingDouble(PackRollService.WeightedOdds::probability).reversed());

        int slot = 0;
        for (PackRollService.WeightedOdds drop : drops) {
            if (slot >= DROP_SLOTS) {
                break;
            }
            builder.item(slot++, buildDropIcon(drop));
        }
        builder.fill(IntStream.range(slot, DROP_SLOTS), GuiIcons.filler());
        builder.fill(IntStream.range(DROP_SLOTS, 54).filter(s -> s < TIER_ROW || s > TIER_ROW + 3)
                .filter(s -> s != CLOSE_SLOT && s != AUTO_SLOT && s != DROP_SLOTS + 4), GuiIcons.filler());

        builder.item(DROP_SLOTS + 4, buildChaseIcon(egg, player));
        int[] tiers = PackOpenService.HATCH_TIERS;
        for (int i = 0; i < tiers.length; i++) {
            int count = tiers[i];
            builder.item(TIER_ROW + i, buildTierIcon(egg, player, count),
                    (clicker, event) -> hatch(clicker, egg, count));
        }
        builder.item(AUTO_SLOT, buildAutoIcon(player), (clicker, event) -> toggleAuto(clicker, egg.id()));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    /**
     * Auto-hatch's only home now that the storage screen is gone - which is
     * where it belonged anyway, since it only does anything while the
     * player is stood at an egg (see {@code PackOpenService#autoHatchTick}).
     * <p>
     * Turning it on does NOT start it. The rung buttons do that: with
     * auto-hatch on, clicking "Hatch 5x" means "keep doing five at a time",
     * which is one decision made with the same click instead of a toggle
     * plus a hidden amount the player has to go and find somewhere else.
     */
    private ItemStack buildAutoIcon(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        boolean on = profile.isAutoOpenEnabled();
        ItemBuilder builder = ItemBuilder.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, on ? "Auto-Hatch: ON" : "Auto-Hatch: OFF"));
        if (on) {
            builder.lore("&7Hatching &f" + profile.getAutoHatchAmount() + "x&7 at a time while");
            builder.lore("&7you stand at an egg.");
            builder.lore("");
            builder.lore("&7Pick another amount below to");
            builder.lore("&7change it.");
        } else {
            builder.lore("&7Turn this on, then pick an");
            builder.lore("&7amount below - it will keep");
            builder.lore("&7hatching that many while you");
            builder.lore("&7stand at an egg.");
        }
        builder.lore("");
        builder.lore("&8[CLICK] &fTo " + (on ? "Disable" : "Enable"));
        return builder.hideAttributes().build();
    }

    private void toggleAuto(Player player, String packId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        boolean enabled = !profile.isAutoOpenEnabled();
        profile.setAutoOpenEnabled(enabled);
        store.save(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, enabled ? 1.4f : 1f);
        player.sendMessage(Text.parse(enabled
                ? "<green>Auto-hatch on - now pick how many to hatch at a time.</green>"
                : "<gray>Auto-hatch off.</gray>"));
        open(player, packId);
    }

    /**
     * A rung click means one of two things depending on the toggle: hatch
     * this many now, or (with auto-hatch on) keep hatching this many. Either
     * way it hatches immediately, so the click always does something
     * visible.
     */
    private void hatch(Player player, PackDefinition egg, int count) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (profile.isAutoOpenEnabled()) {
            profile.setAutoHatchAmount(count);
            store.save(player.getUniqueId());
            player.sendMessage(Text.parse(
                    "<green>Auto-hatching <amount>x at a time.</green>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                            "amount", String.valueOf(count))));
        }
        // The hatch plays out in the world, in front of the player - the
        // menu has to be out of the way to see it.
        player.closeInventory();
        PackRollService.PurchaseResult result = openService.tryHatch(player, egg.id(), count);
        if (!result.success() && result.failureReason() != null) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
    }

    private ItemStack buildDropIcon(PackRollService.WeightedOdds drop) {
        ItemDefinition item = drop.item();
        Rarity rarity = content.get().rarities().find(item.rarityId()).orElse(null);
        // baseIcon only resolves the ICON - a head or a material - and never
        // names it, so without this every pet in the list read "Player Head".
        ItemBuilder builder = iconFactory.baseIcon(item).name(item.displayName());
        builder.lore("");
        if (rarity != null) {
            builder.lore(rarity.displayName());
        }
        builder.lore("&7Chance: &f" + PackOddsLore.formatChance(drop.probability()));
        return builder.hideAttributes().build();
    }

    /** The luck/Huge/Shiny block, given its own slot so it reads as a property of the egg rather than of any one pet. */
    private ItemStack buildChaseIcon(PackDefinition egg, Player player) {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName(ACCENT, "Your Odds"));
        List<String> chase = oddsLore.chaseLines(egg, player);
        if (chase.isEmpty()) {
            builder.lore("&7Nothing extra on this egg.");
        } else {
            chase.forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    private ItemStack buildTierIcon(PackDefinition egg, Player player, int count) {
        boolean gamepassed = count <= openService.maxTierFor(player);
        boolean affordable = rollService.affordableHatches(player, egg.id(), count) >= count;
        Material material = !gamepassed ? Material.BARRIER : affordable ? Material.LIME_DYE : Material.GRAY_DYE;

        ItemBuilder builder = ItemBuilder.of(material)
                .name(MenuLore.buttonName(ACCENT, "Hatch " + count + "x"));
        BigInteger coins = BigInteger.valueOf(egg.coinCost()).multiply(BigInteger.valueOf(count));
        BigInteger diamonds = BigInteger.valueOf(egg.diamondCost()).multiply(BigInteger.valueOf(count));
        builder.lore("&7Cost: &a$" + Formatting.format(coins)
                + (diamonds.signum() > 0 ? " &8+ &b" + Formatting.format(diamonds) + " diamonds" : ""));
        builder.lore("");
        boolean auto = store.getOrCreate(player.getUniqueId()).isAutoOpenEnabled();
        if (!gamepassed) {
            builder.lore("&cNeeds the Multi-Hatch gamepass.");
        } else if (!affordable) {
            builder.lore("&cYou can't afford this yet.");
        } else if (auto) {
            builder.lore("&8[CLICK] &fTo Auto-Hatch " + count + "x");
        } else {
            builder.lore("&8[CLICK] &fTo Hatch");
        }
        return builder.hideAttributes().build();
    }
}
