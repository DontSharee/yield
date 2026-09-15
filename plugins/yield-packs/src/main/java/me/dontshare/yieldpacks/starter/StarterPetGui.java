package me.dontshare.yieldpacks.starter;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Shown once, on a brand-new player's first join - pick a dog or a cat.
 * Closing without picking (the "×" button, Escape, anything else) grants a
 * random one instead ({@link StarterPetService#grantRandom}), so nobody ends
 * up with nothing just because they dismissed the screen.
 */
public final class StarterPetGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int PUP_SLOT = 11;
    private static final int KITTEN_SLOT = 15;

    private final GuiManager guiManager;
    private final ItemIconFactory iconFactory;
    private final Supplier<ItemRegistry> itemRegistry;
    private final StarterPetService service;

    public StarterPetGui(GuiManager guiManager, ItemIconFactory iconFactory, Supplier<ItemRegistry> itemRegistry, StarterPetService service) {
        this.guiManager = guiManager;
        this.iconFactory = iconFactory;
        this.itemRegistry = itemRegistry;
        this.service = service;
    }

    public void open(Player player) {
        boolean[] chosen = {false};

        var builder = Gui.builder(3, "Choose Your Starter Pet");
        builder.fill(IntStream.range(0, 27), GuiIcons.filler());
        builder.item(PUP_SLOT, petIcon("starter_pup"), (clicker, event) -> {
            chosen[0] = true;
            service.grant(clicker, "starter_pup");
            clicker.closeInventory();
        });
        builder.item(KITTEN_SLOT, petIcon("starter_kitten"), (clicker, event) -> {
            chosen[0] = true;
            service.grant(clicker, "starter_kitten");
            clicker.closeInventory();
        });
        builder.onClose(closer -> {
            if (!chosen[0]) {
                service.grantRandom(closer);
            }
        });

        guiManager.open(player, builder.build());
    }

    private ItemStack petIcon(String itemId) {
        ItemDefinition item = itemRegistry.get().find(itemId).orElse(null);
        if (item == null) {
            return ItemBuilder.of(org.bukkit.Material.BARRIER).name("&cMissing item: " + itemId).build();
        }
        ItemBuilder builder = iconFactory.baseIcon(item).name(MenuLore.buttonName(ACCENT, item.displayName()));
        MenuLore.button(
                "starter pet",
                List.of(" &7A loyal companion to", " &7fight alongside you."),
                ACCENT,
                "Click to Choose"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
