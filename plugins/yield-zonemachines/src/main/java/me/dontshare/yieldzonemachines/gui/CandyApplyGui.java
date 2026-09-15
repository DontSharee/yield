package me.dontshare.yieldzonemachines.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.leveling.Candy;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzonemachines.ZoneMachineService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Opened by smacking a CANDY_APPLY zone machine (see {@code
 * ZoneMachineDisplay#handleClick}) - one real drag-and-drop slot, exactly
 * like Forge/Enchants: drop a candy in and it's immediately fed to your
 * strongest equipped pet (see {@link ZoneMachineService#strongestEquippedPet}),
 * consuming exactly 1 and leaving any remainder of the stack sitting in the
 * slot for another drop. Anything that isn't a real candy bounces straight
 * back to the player's inventory rather than sitting in the slot.
 */
public final class CandyApplyGui {

    private static final int CANDY_SLOT = 13;
    private static final int CLOSE_SLOT = 22;
    private static final String ACCENT = MenuLore.ACCENT;

    private final YieldPacks packs;
    private final ZoneMachineService machineService;
    private final GuiManager guiManager;

    public CandyApplyGui(YieldPacks packs, ZoneMachineService machineService, GuiManager guiManager) {
        this.packs = packs;
        this.machineService = machineService;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        var builder = Gui.builder(3, "Candy Apply");
        // CANDY_SLOT is left genuinely empty - same as every other real
        // drag-and-drop input slot in the game (Forge, Enchants) - so it has
        // to be excluded from the filler pass rather than just declared
        // editable afterwards, which would leave a pane sitting in it.
        builder.fill(IntStream.range(0, 27).filter(slot -> slot != CANDY_SLOT), GuiIcons.filler());
        builder.item(4, hintIcon());
        builder.editableSlots(IntStream.of(CANDY_SLOT));

        Gui[] self = new Gui[1];
        // Identity, not "some Gui": a deferred callback landing after the
        // player moved to another screen would otherwise read that screen's
        // slot 13 and act on whatever it found there.
        builder.onEditableSlotChange(clicker -> {
            if (clicker.getOpenInventory().getTopInventory().getHolder() == self[0]) {
                onSlotChanged(clicker, self[0]);
            }
        });
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        Gui gui = builder.build();
        self[0] = gui;
        // Slot 13 holds the player's own real candy. Without this, closing
        // the screen with any of the stack left simply destroyed it - the
        // normal outcome of feeding one candy from a stack, not an edge case.
        gui.setCloseHandler(clicker -> returnCandy(clicker, gui));
        guiManager.open(player, gui);
    }

    private void returnCandy(Player player, Gui gui) {
        ItemStack remaining = gui.getInventory().getItem(CANDY_SLOT);
        if (remaining == null || remaining.getType().isAir()) {
            return;
        }
        // Cleared as well as handed back: the Gui instance outlives this
        // close, and returning from a slot that still holds the stack would
        // hand it over twice if it were ever reopened.
        gui.getInventory().setItem(CANDY_SLOT, null);
        giveOrDrop(player, remaining);
    }

    private void onSlotChanged(Player player, Gui gui) {
        Inventory top = gui.getInventory();
        ItemStack current = top.getItem(CANDY_SLOT);
        if (current == null || current.getType().isAir()) {
            return;
        }

        Optional<Candy> candy = packs.getPetLevelingService().candyFor(current);
        if (candy.isEmpty()) {
            top.setItem(CANDY_SLOT, null);
            giveOrDrop(player, current);
            player.sendMessage(Text.parse("<red>That's not a candy.</red>"));
            return;
        }

        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        UUID strongest = machineService.strongestEquippedPet(profile);
        if (strongest == null) {
            top.setItem(CANDY_SLOT, null);
            giveOrDrop(player, current);
            player.sendMessage(Text.parse("<red>You don't have any pets equipped to feed.</red>"));
            return;
        }
        if (!packs.getPetLevelingService().feedCandy(player, strongest, candy.get())) {
            top.setItem(CANDY_SLOT, null);
            giveOrDrop(player, current);
            player.sendMessage(Text.parse("<red>Couldn't feed that pet.</red>"));
            return;
        }

        current.setAmount(current.getAmount() - 1);
        top.setItem(CANDY_SLOT, current.getAmount() <= 0 ? null : current);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.0f);
        player.sendMessage(Text.parse("<green>Fed " + candy.get().displayName() + " to your strongest pet!</green>"));
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private ItemStack hintIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.HONEY_BOTTLE).name(MenuLore.infoName(ACCENT, "DROP A CANDY"));
        MenuLore.info("candy apply", List.of(" &7Drag a candy here to feed it", " &7to your strongest equipped pet."),
                ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
