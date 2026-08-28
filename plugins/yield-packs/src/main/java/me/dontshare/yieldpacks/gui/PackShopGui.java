package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.dialog.QuantityPickerDialog;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** The pack shop screen - a scrollable grid of pack rows, opening the quantity-picker Dialog on click. */
public final class PackShopGui {

    private static final String ACCENT = "<#4BD9FF>";

    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final GuiManager guiManager;
    private final QuantityPickerDialog quantityPickerDialog;

    public PackShopGui(Supplier<PackContentLoader.ContentSnapshot> content, GuiManager guiManager,
                        QuantityPickerDialog quantityPickerDialog) {
        this.content = content;
        this.guiManager = guiManager;
        this.quantityPickerDialog = quantityPickerDialog;
    }

    public void open(Player player) {
        List<PackDefinition> packs = content.get().packs().all();
        int contentRows = Math.max(1, Math.min(5, (packs.size() + 8) / 9));
        int totalRows = contentRows + 1; // last row reserved for controls

        var builder = Gui.builder(totalRows, "<#4BD9FF><bold>Packs</bold>");
        int slot = 0;
        for (PackDefinition pack : packs) {
            if (slot >= contentRows * 9) {
                break;
            }
            builder.item(slot, buildIcon(pack), (clicker, event) -> quantityPickerDialog.open(clicker, pack));
            slot++;
        }
        builder.fill(IntStream.range(contentRows * 9, totalRows * 9), GuiIcons.filler());
        builder.item(totalRows * 9 - 5, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private ItemStack buildIcon(PackDefinition pack) {
        ItemBuilder builder = ItemBuilder.of(pack.material())
                .name(pack.displayName() + " &7[" + Formatting.fancyFont("click") + "]");
        if (pack.customModelData() != null) {
            builder.modelData(pack.customModelData());
        }
        MenuLore.button(
                "pack",
                List.of(" &7Open this pack", " &7for a chance at", " &fnew pets&7!"),
                ACCENT,
                "Open Pack",
                "Click",
                "Click to Choose Quantity"
        ).forEach(builder::lore);
        return builder
                .lore("")
                .lore("&7Cost: &a$" + Formatting.spaced(pack.coinCost()) + " &8| &e" + pack.gemCost() + " gems")
                .hideAttributes()
                .build();
    }
}
