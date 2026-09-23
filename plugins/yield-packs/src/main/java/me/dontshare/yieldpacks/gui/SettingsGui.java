package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
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
 * Player preference toggles that don't need their own command - Send
 * Mode's Single/All (only meaningful while {@code SendMode.MANUAL} is
 * active, see /sendmode) and the rare pet hatch animation - and the
 * natural home for any future simple on/off preference rather than
 * minting a new slash command for each one.
 */
public final class SettingsGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 3;
    private static final int TOGGLE_SLOT = 11;
    private static final int RARE_ANIMATION_SLOT = 15;
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
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9)
                .filter(slot -> slot != TOGGLE_SLOT && slot != RARE_ANIMATION_SLOT && slot != CLOSE_SLOT), GuiIcons.filler());
        builder.item(TOGGLE_SLOT, buildAttackModeToggle(profile), (clicker, event) -> toggleAttackMode(clicker));
        builder.item(RARE_ANIMATION_SLOT, buildRareAnimationToggle(profile), (clicker, event) -> toggleRareAnimation(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private void toggleAttackMode(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAttackMode(profile.getAttackMode() == AttackMode.SINGLE ? AttackMode.ALL : AttackMode.SINGLE);
        store.save(player.getUniqueId());
        open(player);
    }

    private void toggleRareAnimation(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setRarePetAnimationEnabled(!profile.isRarePetAnimationEnabled());
        store.save(player.getUniqueId());
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f,
                profile.isRarePetAnimationEnabled() ? 1.4f : 1f);
        open(player);
    }

    /**
     * Whether a rare pull shakes, flies into the player's view with a title
     * card, and drops back into its slot. On by default - it is the best
     * moment in the game - but it is a second and a half in your face, so
     * anyone grinding hatches can turn it off and keep the glow and size.
     */
    private ItemStack buildRareAnimationToggle(PackPlayerProfile profile) {
        boolean on = profile.isRarePetAnimationEnabled();
        ItemBuilder builder = ItemBuilder.of(on ? Material.NETHER_STAR : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, "RARE PET ANIMATION: " + (on ? "ON" : "OFF")));
        MenuLore.button(
                "settings",
                List.of(
                        " &7When you hatch a Legendary or",
                        " &7better (or anything 1 in 1,000+),",
                        " &7it shakes and pops into your",
                        " &7screen before landing.",
                        on ? " &7Off keeps just the glow." : " &7Currently: glow only."
                ),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        if (on) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
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
