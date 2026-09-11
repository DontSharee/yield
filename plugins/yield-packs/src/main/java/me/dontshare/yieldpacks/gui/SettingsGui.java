package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.AttackMode;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Player preference toggles that don't need their own command - starts with
 * just one (Send Mode's Single/All - only meaningful while {@code
 * SendMode.MANUAL} is active, see /sendmode), but is the natural home for
 * any future simple on/off-style preference rather than minting a new
 * slash command for each one.
 */
public final class SettingsGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 3;
    private static final int TOGGLE_SLOT = 13;
    private static final int CLOSE_SLOT = 22;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final GuiManager guiManager;

    public SettingsGui(PlayerDataStore<PackPlayerProfile> store, GuiManager guiManager) {
        this.store = store;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());

        var builder = Gui.builder(TOTAL_ROWS, "Settings");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9).filter(slot -> slot != TOGGLE_SLOT && slot != CLOSE_SLOT), GuiIcons.filler());
        builder.item(TOGGLE_SLOT, buildAttackModeToggle(profile), (clicker, event) -> toggleAttackMode(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private void toggleAttackMode(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAttackMode(profile.getAttackMode() == AttackMode.SINGLE ? AttackMode.ALL : AttackMode.SINGLE);
        store.save(player.getUniqueId());
        open(player);
    }

    private ItemStack buildAttackModeToggle(PackPlayerProfile profile) {
        boolean all = profile.getAttackMode() == AttackMode.ALL;
        ItemBuilder builder = ItemBuilder.of(all ? Material.TNT : Material.ARROW)
                .name(MenuLore.buttonName(ACCENT, all ? "MULTI SEND" : "SINGLE SEND"));
        MenuLore.button(
                "settings",
                List.of(
                        " &7Only used while &f/sendmode manual&7:",
                        " &7Single Send - each click sends one pet.",
                        " &7Multi Send - each click sends the whole squad."
                ),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        if (all) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }
}
