package me.dontshare.yieldmining.forge;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * "/forge" - a real drag-and-drop input zone, exactly 5 slots (20-24, the
 * middle of row 3): drag Special Ore in from your own inventory (or shift-
 * click it in), see the running combined Multiplier update live, then
 * click FORGE to consume everything sitting there and produce one held
 * item. Anything dropped in that isn't Special Ore bounces straight back
 * out. Closing with ore still in the grid returns it to your inventory
 * rather than losing it.
 */
public final class ForgeGui {

    private static final int[] INPUT_SLOTS = {20, 21, 22, 23, 24};
    private static final Set<Integer> INPUT_SLOT_SET = Set.of(20, 21, 22, 23, 24);
    private static final int FORGE_SLOT = 37;
    private static final int INFO_SLOT = 39;
    private static final String ACCENT = MenuLore.ACCENT;

    private final GuiManager guiManager;
    private final SpecialOreItem specialOreItem;
    private final ForgedItem forgedItem;

    public ForgeGui(GuiManager guiManager, SpecialOreItem specialOreItem, ForgedItem forgedItem) {
        this.guiManager = guiManager;
        this.specialOreItem = specialOreItem;
        this.forgedItem = forgedItem;
    }

    public void open(Player player) {
        GuiBuilder builder = Gui.builder(5, "Mining Forge")
                .fill(IntStream.range(0, 27).filter(slot -> !INPUT_SLOT_SET.contains(slot)), greenPane())
                .fill(IntStream.range(27, 45), grayPane())
                .editableSlots(IntStream.of(INPUT_SLOTS))
                .item(INFO_SLOT, buildInfoIcon(0, 0))
                .item(FORGE_SLOT, buildForgeButton(0, 0), (clicker, event) -> attemptForge(clicker))
                .onEditableSlotChange(this::onGridChanged);

        Gui gui = builder.build();
        gui.setCloseHandler(clicker -> returnGridItems(clicker, gui.getInventory()));
        guiManager.open(player, gui);
    }

    /** Rejects anything that isn't Special Ore (bounces it back to the player), then recomputes and redraws the running total. Runs the tick after a grid click/drag actually resolves. */
    private void onGridChanged(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        Inventory top = gui.getInventory();
        for (int slot : INPUT_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir() && !specialOreItem.isSpecialOre(item)) {
                top.setItem(slot, null);
                giveOrDrop(player, item);
            }
        }

        double totalMultiplier = 0;
        int count = 0;
        for (int slot : INPUT_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item != null && specialOreItem.isSpecialOre(item)) {
                totalMultiplier += specialOreItem.multiplierOf(item) * item.getAmount();
                count += item.getAmount();
            }
        }
        gui.set(INFO_SLOT, buildInfoIcon(count, totalMultiplier), null);
        gui.set(FORGE_SLOT, buildForgeButton(count, totalMultiplier), (clicker, event) -> attemptForge(clicker));
    }

    private void attemptForge(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        Inventory top = gui.getInventory();

        double totalMultiplier = 0;
        int consumed = 0;
        for (int slot : INPUT_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item != null && specialOreItem.isSpecialOre(item)) {
                totalMultiplier += specialOreItem.multiplierOf(item) * item.getAmount();
                consumed += item.getAmount();
                top.setItem(slot, null);
            }
        }

        if (consumed == 0) {
            player.sendMessage(Text.parse("<red>Put some Special Ore in the grid first!</red>"));
            return;
        }

        ForgeStatType[] types = ForgeStatType.values();
        ForgeStatType type = types[ThreadLocalRandom.current().nextInt(types.length)];
        giveOrDrop(player, forgedItem.create(type, totalMultiplier));

        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 0.8f);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.8f);
        player.sendMessage(Text.parse("<green>Forged " + consumed + " Special Ore into a held item!</green>"));

        gui.set(INFO_SLOT, buildInfoIcon(0, 0), null);
        gui.set(FORGE_SLOT, buildForgeButton(0, 0), (clicker, event) -> attemptForge(clicker));
    }

    private void returnGridItems(Player player, Inventory top) {
        for (int slot : INPUT_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                giveOrDrop(player, item);
            }
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private ItemStack buildInfoIcon(int count, double totalMultiplier) {
        ItemBuilder builder = ItemBuilder.of(Material.ENDER_EYE).name(MenuLore.infoName(ACCENT, "FORGE INFO"));
        MenuLore.info(
                "forge",
                List.of(" &7Combine Special Ore to create", " &7a held item you can assign", " &7to an NPC for a boost."),
                ACCENT,
                List.of("Better ores give better items!")
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildForgeButton(int count, double totalMultiplier) {
        boolean enabled = count > 0;
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName(ACCENT, "FORGE"));
        MenuLore.button(
                "forge",
                List.of(" &7Combine special ores to create", " &7powerful items & multipliers!"),
                ACCENT,
                List.of("Multiplier: &a" + String.format(Locale.ROOT, "%.3f", totalMultiplier) + "x"),
                enabled ? "Click to Start Forging" : "Drag Special Ore Into the Grid First"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack greenPane() {
        return ItemBuilder.of(Material.GREEN_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }

    private ItemStack grayPane() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }
}
