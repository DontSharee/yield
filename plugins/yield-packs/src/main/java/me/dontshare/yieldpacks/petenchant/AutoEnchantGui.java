package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * "Auto Enchant" target picker - toggle any number of Uniques, then Start
 * hands off to {@link PetEnchantTableGui#startAutoEnchant}, which owns the
 * actual roll loop (it needs the live table Gui to update as it runs).
 * Matches the reference "Enchants until you get any selected enchantment"
 * behavior - any ONE of the picked targets landing stops the hunt, not all
 * of them.
 */
public final class AutoEnchantGui {

    private static final int[] UNIQUE_SLOTS = {10, 12, 14, 16};
    private static final int START_SLOT = 22;
    private static final int BACK_SLOT = 31;
    private static final String ACCENT = "<#B15CFF>";

    private final PetEnchantService enchantService;
    private final Supplier<PetEnchantContent> content;
    private final GuiManager guiManager;
    private PetEnchantTableGui tableGui;

    public AutoEnchantGui(PetEnchantService enchantService, Supplier<PetEnchantContent> content, GuiManager guiManager) {
        this.enchantService = enchantService;
        this.content = content;
        this.guiManager = guiManager;
    }

    /** Set once, right after both GUIs exist - see {@code PetEnchantSelectGui#setTableGui}'s own javadoc on this pattern. */
    public void setTableGui(PetEnchantTableGui tableGui) {
        this.tableGui = tableGui;
    }

    public void open(Player player) {
        var builder = Gui.builder(4, "Auto Enchant Targets");
        builder.fill(IntStream.range(0, 36), GuiIcons.filler());

        List<PetUniqueDefinition> uniques = content.get().uniques();
        Set<String> selected = enchantService.autoTargets(player);
        for (int i = 0; i < uniques.size() && i < UNIQUE_SLOTS.length; i++) {
            PetUniqueDefinition unique = uniques.get(i);
            builder.item(UNIQUE_SLOTS[i], buildToggleIcon(unique, selected.contains(unique.id())), (clicker, e) -> {
                enchantService.toggleAutoTarget(clicker, unique.id());
                open(clicker);
            });
        }

        builder.item(START_SLOT, buildStartButton(selected), (clicker, e) -> tableGui.startAutoEnchant(clicker));
        builder.item(BACK_SLOT, buildBackButton(), (clicker, e) -> tableGui.open(clicker));

        guiManager.open(player, builder.build());
    }

    private ItemStack buildToggleIcon(PetUniqueDefinition unique, boolean selected) {
        String accent = "<" + unique.colorHex() + ">";
        ItemBuilder builder = ItemBuilder.of(selected ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(accent + "<bold>" + unique.displayName().toUpperCase(Locale.ROOT) + "</bold>"
                        + (selected ? " &a[✓ Targeted]" : ""));
        MenuLore.button(
                "auto enchant",
                List.of(" &7Stop rolling once this pet", " &7lands " + unique.displayName() + "."),
                accent,
                selected ? "Click to Un-target" : "Click to Target"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildStartButton(Set<String> selected) {
        boolean enabled = !selected.isEmpty();
        ItemBuilder builder = ItemBuilder.of(Material.LIME_CONCRETE)
                .name(enabled ? MenuLore.buttonName(ACCENT, "START") : "&7&lSTART");
        MenuLore.button(
                "auto enchant",
                List.of(" &7Rolls this pet repeatedly,", " &7spending gems each time, until", " &7it lands ANY targeted enchant."),
                ACCENT,
                enabled ? "Click to Start" : "Target an Enchant First"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ARROW).name(MenuLore.buttonName(ACCENT, "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the Enchanting Table"), ACCENT, "Click to Go Back").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
