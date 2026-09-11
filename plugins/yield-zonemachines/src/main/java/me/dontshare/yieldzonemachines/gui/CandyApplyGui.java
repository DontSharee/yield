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
        builder.fill(IntStream.range(0, 27), GuiIcons.filler());
        builder.item(4, hintIcon());
        // CANDY_SLOT itself is left genuinely empty (no item set) - same as
        // every other real drag-and-drop input slot in the game (Forge,
        // Enchants) - a pre-placed icon there would just be one more thing
        // the player has to displace before they can actually drop a candy in.
        builder.editableSlots(IntStream.of(CANDY_SLOT));
        builder.onEditableSlotChange(this::onSlotChanged);
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private void onSlotChanged(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
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
