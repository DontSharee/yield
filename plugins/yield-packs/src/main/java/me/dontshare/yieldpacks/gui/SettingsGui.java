package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.AttackMode;
import me.dontshare.yieldpacks.player.CombatPerks;
import me.dontshare.yieldcore.text.Text;
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
    /** Four toggles centred across the middle row - how pets fight first, then taps, then hatching. */
    private static final int AUTO_ATTACK_SLOT = 11;
    private static final int TOGGLE_SLOT = 12;
    private static final int AUTO_TAP_SLOT = 14;
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
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());
        builder.item(AUTO_ATTACK_SLOT, buildAutoAttackToggle(player, profile), (clicker, event) -> toggleAutoAttack(clicker));
        builder.item(AUTO_TAP_SLOT, buildAutoTapToggle(player, profile), (clicker, event) -> toggleAutoTap(clicker));
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

    private void toggleAutoAttack(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.applyAutoAttack(!profile.isAutoAttackOn());
        store.save(player.getUniqueId());
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f,
                profile.isAutoAttackOn() ? 1.4f : 1f);
        open(player);
    }

    /**
     * Auto Attack - free for everyone, and on by default, as in Pet
     * Simulator 99: pets pick their own cubes and a click redirects the
     * whole squad (and taps it). Off means nothing fights until you click.
     */
    private ItemStack buildAutoAttackToggle(Player viewer, PackPlayerProfile profile) {
        boolean auto = profile.isAutoAttackOn();
        boolean premium = CombatPerks.hasPremium(viewer);
        ItemBuilder builder = ItemBuilder.of(auto ? Material.CLOCK : Material.LEVER)
                .name(MenuLore.buttonName(ACCENT, "AUTO ATTACK: " + (auto ? "ON" : "OFF") + (premium ? " &6[Premium]" : "")));
        MenuLore.button(
                "settings",
                !auto
                        ? List.of(" &7Off: no pet fights until you", " &7click a cube.")
                        : premium
                        ? List.of(" &7Pets pick their own cubes;", " &7click one to send them all", " &7to it. Re-engaging 2x faster", " &7after each kill (Premium).")
                        : List.of(" &7Pets pick their own cubes;", " &7click one to send them all", " &7to it - clicks tap it too."),
                ACCENT,
                "Click to Toggle"
        ).forEach(builder::lore);
        if (auto) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    private void toggleAutoTap(Player player) {
        if (!CombatPerks.hasAutoTap(player)) {
            player.sendMessage(Text.parse("<gray>Auto Tap taps your pets' cube for you. Get it at <white>/buy</white>.</gray>"));
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        profile.setAutoTapEnabled(!profile.isAutoTapEnabled());
        store.save(player.getUniqueId());
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f,
                profile.isAutoTapEnabled() ? 1.4f : 1f);
        open(player);
    }

    /** Auto Tap - a bought perk, so shown to everyone (it is how they find out it exists) but only switchable by owners. */
    private ItemStack buildAutoTapToggle(Player viewer, PackPlayerProfile profile) {
        if (!CombatPerks.hasAutoTap(viewer)) {
            ItemBuilder locked = ItemBuilder.of(Material.GRAY_DYE).name("&7AUTO TAP");
            MenuLore.info("settings", List.of(" &7Taps your pets' cube for you,", " &7four times a second.",
                    " &7Get it at &f/buy&7."), ACCENT, List.of()).forEach(locked::lore);
            return locked.hideAttributes().build();
        }
        boolean on = profile.isAutoTapEnabled();
        ItemBuilder builder = ItemBuilder.of(on ? Material.GOLDEN_HOE : Material.WOODEN_HOE)
                .name(MenuLore.buttonName(ACCENT, "AUTO TAP: " + (on ? "ON" : "OFF")));
        MenuLore.button("settings", List.of(" &7Taps your pets' cube for you,", " &7four times a second."),
                ACCENT, "Click to Toggle").forEach(builder::lore);
        if (on) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
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
                        "Single Send: one pet per click. With",
                        "Auto Attack on, pets spread out over",
                        "different cubes.",
                        "Multi Send: the whole squad goes",
                        "together."
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
