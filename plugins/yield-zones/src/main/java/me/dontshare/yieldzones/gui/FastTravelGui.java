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
import me.dontshare.yieldzones.zone.ZoneLockService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * One icon per configured zone, teleporting to {@link ZoneDefinition#teleport()}
 * on click if the player already has it - clicking a locked zone instead
 * opens {@link ZonePurchaseGui} for it, exactly like walking into its wall
 * would, so fast travel doubles as a way to shop for zones you haven't
 * reached yet.
 */
public final class FastTravelGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int ROWS = 3;
    private static final int GRID_START = 9;

    private final GuiManager guiManager;
    private final Supplier<Map<String, ZoneDefinition>> zones;
    private final ZoneLockService lockService;
    private final ZonePurchaseGui purchaseGui;

    public FastTravelGui(GuiManager guiManager, Supplier<Map<String, ZoneDefinition>> zones,
                          ZoneLockService lockService, ZonePurchaseGui purchaseGui) {
        this.guiManager = guiManager;
        this.zones = zones;
        this.lockService = lockService;
        this.purchaseGui = purchaseGui;
    }

    public void open(Player player) {
        List<ZoneDefinition> list = new ArrayList<>(zones.get().values());
        // Border top + bottom rows, however many middle rows the zone count actually needs (capped at a full 6-row chest).
        int middleRows = Math.max(1, (int) Math.ceil(list.size() / 9.0));
        int rows = Math.max(ROWS, Math.min(6, middleRows + 2));
        GuiBuilder builder = Gui.builder(rows, "Fast Travel")
                .fill(IntStream.range(0, 9), GuiIcons.filler())
                .fill(IntStream.range((rows - 1) * 9, rows * 9), GuiIcons.filler())
                .item((rows - 1) * 9 + 4, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        int slot = GRID_START;
        for (ZoneDefinition zone : list) {
            if (slot >= (rows - 1) * 9) {
                break;
            }
            boolean unlocked = lockService.isUnlocked(player, zone);
            builder.item(slot, zoneIcon(zone, unlocked), (clicker, event) -> {
                if (unlocked) {
                    travel(clicker, zone);
                } else {
                    clicker.closeInventory();
                    purchaseGui.open(clicker, zone);
                }
            });
            slot++;
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack zoneIcon(ZoneDefinition zone, boolean unlocked) {
        String name = Formatting.stripLeadingColorCodes(zone.displayName());
        List<String> lore = unlocked
                ? MenuLore.button("zone", List.of(), ACCENT, "Click to Travel")
                : MenuLore.purchase("zone", List.of(" &7This zone is locked."), "<red>", List.of(), "Click to View Unlock Cost");

        Material material = unlocked ? Material.ENDER_PEARL : Material.TINTED_GLASS;
        return ItemBuilder.of(material)
                .name((unlocked ? ACCENT : "&7") + "&l" + name + (unlocked ? "" : " &7[LOCKED]"))
                .lore(lore)
                .hideAttributes()
                .build();
    }

    private void travel(Player player, ZoneDefinition zone) {
        player.closeInventory();
        player.teleport(zone.teleport());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.sendMessage(Text.parse("<#4BD9FF>Teleported to <name>.</#4BD9FF>",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(zone.displayName()))));
    }
}
