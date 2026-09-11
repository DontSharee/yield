package me.dontshare.yieldzones.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.data.ZoneUnlockCost;
import me.dontshare.yieldzones.zone.ZoneLockService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * The 3x3 "dispenser" screen a locked zone's wall shows - a single Purchase
 * button dead center (slot 4, matching a real dispenser's own dispensing
 * slot) with every other slot filler. Opened either by clicking a wall
 * block or by simply walking up to one - see {@code ZoneLockService}.
 */
public final class ZonePurchaseGui {

    private static final String ACCENT = "<#4BD9FF>";

    private final GuiManager guiManager;
    private final ZoneLockService lockService;

    public ZonePurchaseGui(GuiManager guiManager, ZoneLockService lockService) {
        this.guiManager = guiManager;
        this.lockService = lockService;
    }

    public void open(Player player, ZoneDefinition zone) {
        if (lockService.isUnlocked(player, zone)) {
            // Bought it (or had a bypass permission granted) since the wall
            // was last rendered for them - nothing to show.
            return;
        }
        String title = Formatting.stripLeadingColorCodes(zone.displayName()) + " (Locked)";
        GuiBuilder builder = Gui.builder(InventoryType.DISPENSER, title)
                .fill(IntStream.of(0, 1, 2, 3, 5, 6, 7, 8), GuiIcons.filler())
                .item(4, purchaseIcon(zone), (clicker, event) -> attemptPurchase(clicker, zone));
        guiManager.open(player, builder.build());
    }

    private ItemStack purchaseIcon(ZoneDefinition zone) {
        ZoneUnlockCost cost = zone.unlockCost();
        List<String> data = new ArrayList<>();
        if (cost.coins().signum() > 0) {
            data.add("Coins: &6" + Formatting.format(cost.coins()));
        }
        if (cost.gems().signum() > 0) {
            data.add("Gems: &b" + Formatting.format(cost.gems()));
        }
        for (ZoneUnlockCost.ItemCost item : cost.items()) {
            data.add(prettify(item.material()) + ": &f" + item.amount());
        }
        List<String> description = List.of(" &7Purchase access to this zone.");
        List<String> lore = MenuLore.purchase("zone unlock", description, ACCENT, data, "Click to Purchase");

        return ItemBuilder.of(Material.TINTED_GLASS)
                .name(MenuLore.buttonName(ACCENT, "UNLOCK ZONE"))
                .lore(lore)
                .hideAttributes()
                .build();
    }

    private void attemptPurchase(Player player, ZoneDefinition zone) {
        ZoneLockService.PurchaseResult result = lockService.attemptPurchase(player, zone);
        switch (result) {
            case SUCCESS -> {
                player.closeInventory();
                player.sendMessage(Text.parse("<green>Unlocked <name>!</green>",
                        Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(zone.displayName()))));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30, 0.5, 0.6, 0.5, 0.03);
            }
            case ALREADY_UNLOCKED -> {
                player.closeInventory();
                player.sendMessage(Text.parse("<gray>You already have access to this zone.</gray>"));
            }
            case INSUFFICIENT_FUNDS -> {
                player.sendMessage(Text.parse("<red>You can't afford this zone yet.</red>"));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            }
            case MISSING_ITEMS -> {
                player.sendMessage(Text.parse("<red>You're missing some of the required items.</red>"));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            }
        }
    }

    private String prettify(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
