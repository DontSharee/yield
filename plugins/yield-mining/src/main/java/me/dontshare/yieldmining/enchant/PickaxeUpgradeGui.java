package me.dontshare.yieldmining.enchant;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * "/pickaxe" (also opened by right-clicking any pickaxe) - a paginated grid
 * of every configured enchant, 21 per page, matching the reference GUI's
 * exact slot layout (info book at 4, a divider row, 3 rows of enchant
 * icons, page arrows at 48/50).
 */
public final class PickaxeUpgradeGui {

    private static final int PAGE_SIZE = 21;
    private static final int[] CONTENT_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int INFO_SLOT = 4;
    private static final int BACK_PAGE_SLOT = 48;
    private static final int NEXT_PAGE_SLOT = 50;

    private final YieldPacks packs;
    private final PickaxeEnchantService enchantService;
    private final GuiManager guiManager;
    private final PickaxeEnchantMenuGui enchantMenuGui;
    private final Map<UUID, Integer> lastPage = new HashMap<>();

    public PickaxeUpgradeGui(YieldPacks packs, PickaxeEnchantService enchantService, GuiManager guiManager, PickaxeEnchantMenuGui enchantMenuGui) {
        this.packs = packs;
        this.enchantService = enchantService;
        this.guiManager = guiManager;
        this.enchantMenuGui = enchantMenuGui;
    }

    public void open(Player player) {
        open(player, lastPage.getOrDefault(player.getUniqueId(), 0));
    }

    public void open(Player player, int page) {
        List<PickaxeEnchantDefinition> enchants = new ArrayList<>(enchantService.enchants().values());
        int maxPage = Math.max(0, (enchants.size() - 1) / PAGE_SIZE);
        int clampedPage = Math.max(0, Math.min(page, maxPage));
        lastPage.put(player.getUniqueId(), clampedPage);

        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());

        GuiBuilder builder = Gui.builder(6, "Pickaxe Enchants")
                .fill(IntStream.of(0, 1, 2, 3, 5, 6, 7, 8, 45, 46, 47, 49, 51, 52, 53), GuiIcons.filler())
                .fill(IntStream.rangeClosed(10, 16), blackPane())
                .fill(IntStream.of(CONTENT_SLOTS), blackPane())
                .item(INFO_SLOT, buildInfoIcon());

        int start = clampedPage * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < enchants.size(); i++) {
            PickaxeEnchantDefinition def = enchants.get(start + i);
            builder.item(CONTENT_SLOTS[i], buildEnchantIcon(profile, def),
                    (clicker, event) -> openEnchant(clicker, def, clampedPage));
        }

        builder.item(BACK_PAGE_SLOT, GuiIcons.pageArrow(false, clampedPage > 0),
                (clicker, event) -> {
                    if (clampedPage > 0) {
                        open(clicker, clampedPage - 1);
                    }
                });
        builder.item(NEXT_PAGE_SLOT, GuiIcons.pageArrow(true, clampedPage < maxPage),
                (clicker, event) -> {
                    if (clampedPage < maxPage) {
                        open(clicker, clampedPage + 1);
                    }
                });

        guiManager.open(player, builder.build());
    }

    private void openEnchant(Player player, PickaxeEnchantDefinition def, int page) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        if (profile.getRebirths() < def.rebirthRequirement()) {
            player.sendMessage(Text.parse("<red>You don't have the rebirths required for this enchant (need <req>).</red>",
                    Placeholder.unparsed("req", String.valueOf(def.rebirthRequirement()))));
            return;
        }
        enchantMenuGui.open(player, def, page);
    }

    private ItemStack buildEnchantIcon(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        int level = enchantService.levelOf(profile, def.id());
        double chance = enchantService.chanceOf(profile, def);
        List<String> extraLines = List.of(
                "Type: " + def.colorPrimary() + def.type().name(),
                "Level: <white>" + Formatting.format(level) + "</white> <gray>/</gray> <red>" + Formatting.format(def.maxLevel()) + "</red>",
                "Activation: " + def.colorSecondary() + String.format("%.2f%%", chance)
        );
        ItemBuilder builder = ItemBuilder.of(def.displayItem()).name(def.displayName());
        MenuLore.button("enchantment", styledDescription(def), def.colorPrimary(), extraLines, "Open Upgrade Menu")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private List<String> styledDescription(PickaxeEnchantDefinition def) {
        List<String> lines = new ArrayList<>();
        for (String line : def.description()) {
            lines.add("<gray>" + line + "</gray>");
        }
        return lines;
    }

    private ItemStack buildInfoIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.KNOWLEDGE_BOOK).name(MenuLore.infoName("<gold>", "PICKAXE ENCHANTS"));
        MenuLore.info("info", List.of(
                "&fMine blocks&7 to earn &adiamonds&7, then",
                "&fenchant&7 your pickaxe to mine faster."
        ), "<gold>", List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack blackPane() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }
}
