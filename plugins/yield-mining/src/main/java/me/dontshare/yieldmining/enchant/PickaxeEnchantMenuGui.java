package me.dontshare.yieldmining.enchant;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.IntStream;

/** The per-enchant submenu - bulk-buy, disenchant/refund, a preference toggle, and (if configured) mastery. */
public final class PickaxeEnchantMenuGui {

    private static final int[] BUY_AMOUNTS = {1, 10, 25, 50, 100, 250, 500};
    private static final int[] BUY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int PREFERENCES_SLOT = 4;
    private static final int MASTERY_SLOT = 22;
    private static final int DISENCHANT_SLOT = 32;
    private static final int BACK_SLOT = 31;

    private final YieldPacks packs;
    private final PickaxeEnchantService enchantService;
    private final GuiManager guiManager;
    private PickaxeUpgradeGui upgradeGui;

    public PickaxeEnchantMenuGui(YieldPacks packs, PickaxeEnchantService enchantService, GuiManager guiManager) {
        this.packs = packs;
        this.enchantService = enchantService;
        this.guiManager = guiManager;
    }

    /** Resolves the circular reference back to the main menu - set once, right after both GUIs are constructed. */
    public void setUpgradeGui(PickaxeUpgradeGui upgradeGui) {
        this.upgradeGui = upgradeGui;
    }

    public void open(Player player, PickaxeEnchantDefinition def, int page) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        int level = enchantService.levelOf(profile, def.id());

        GuiBuilder builder = Gui.builder(4, "Pickaxe Enchants")
                .fill(IntStream.range(0, 36), blackPane())
                .item(BACK_SLOT, backButton(), (clicker, event) -> upgradeGui.open(clicker, page))
                .item(PREFERENCES_SLOT, buildPreferencesIcon(profile, def), (clicker, event) -> {
                    enchantService.togglePreference(profile, def.id());
                    open(clicker, def, page);
                })
                .item(DISENCHANT_SLOT, buildDisenchantIcon(def, level), (clicker, event) -> attemptDisenchant(clicker, profile, def, page));

        for (int i = 0; i < BUY_AMOUNTS.length; i++) {
            int amount = BUY_AMOUNTS[i];
            builder.item(BUY_SLOTS[i], buildBuyIcon(def, level, amount),
                    (clicker, event) -> attemptPurchase(clicker, profile, def, amount, page));
        }

        if (def.mastery()) {
            builder.item(MASTERY_SLOT, buildMasteryIcon(profile, def, level), (clicker, event) -> attemptMastery(clicker, profile, def, page));
        }

        guiManager.open(player, builder.build());
    }

    private void attemptPurchase(Player player, PackPlayerProfile profile, PickaxeEnchantDefinition def, int amount, int page) {
        PickaxeEnchantService.PurchaseResult result = enchantService.purchase(profile, def, amount);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                packs.getPlayerStore().save(player.getUniqueId());
            }
            case MAXED -> player.sendMessage(Text.parse("<red>This enchant is already maxed!</red>"));
            case CANT_AFFORD -> player.sendMessage(Text.parse("<red>Not enough funds!</red>"));
            case REBIRTH_LOCKED -> player.sendMessage(Text.parse("<red>You don't have the rebirths required for this enchant.</red>"));
        }
        open(player, def, page);
    }

    private void attemptDisenchant(Player player, PackPlayerProfile profile, PickaxeEnchantDefinition def, int page) {
        PickaxeEnchantService.DisenchantResult result = enchantService.disenchant(profile, def);
        if (result == PickaxeEnchantService.DisenchantResult.SUCCESS) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1f);
            packs.getPlayerStore().save(player.getUniqueId());
        } else {
            player.sendMessage(Text.parse("<red>This enchant has no levels to refund!</red>"));
        }
        open(player, def, page);
    }

    private void attemptMastery(Player player, PackPlayerProfile profile, PickaxeEnchantDefinition def, int page) {
        PickaxeEnchantService.MasteryResult result = enchantService.masterUp(profile, def);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1f);
                packs.getPlayerStore().save(player.getUniqueId());
            }
            case NOT_MAXED -> player.sendMessage(Text.parse("<red>This enchant must be maxed level first!</red>"));
            case ALREADY_MASTERED -> player.sendMessage(Text.parse("<red>This enchant's mastery is already maxed!</red>"));
            case CANT_AFFORD -> player.sendMessage(Text.parse("<red>Not enough diamonds to master this enchant!</red>"));
        }
        open(player, def, page);
    }

    private ItemStack buildBuyIcon(PickaxeEnchantDefinition def, int level, int amount) {
        BigInteger price = enchantService.priceForLevels(def, level, amount);
        ItemBuilder builder = ItemBuilder.of(Material.LIME_DYE).name(MenuLore.buttonName("<green>", "UPGRADE " + amount + "x"));
        MenuLore.button("upgrade", List.of(), "<green>",
                List.of("Cost: <white>" + Formatting.format(price) + " " + def.costCurrency().name().toLowerCase()),
                "Click to Upgrade").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildDisenchantIcon(PickaxeEnchantDefinition def, int level) {
        BigInteger refund = enchantService.priceForLevels(def, 0, level);
        ItemBuilder builder = ItemBuilder.of(Material.SMITHING_TABLE).name(MenuLore.buttonName("<red>", "DISENCHANT"));
        MenuLore.button("refund", List.of("Refund all levels of this enchant."), "<red>",
                List.of("Refund: <white>" + Formatting.format(refund) + " " + def.costCurrency().name().toLowerCase()),
                "Click to Disenchant").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildPreferencesIcon(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        boolean enabled = enchantService.isEnabled(profile, def.id());
        ItemBuilder builder = ItemBuilder.of(Material.NAME_TAG).name(MenuLore.buttonName("<yellow>", "PREFERENCES"));
        MenuLore.button("preferences", List.of(), "<yellow>",
                List.of("Status: " + (enabled ? "<green>Enabled" : "<red>Disabled")),
                "Click to Toggle").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildMasteryIcon(PackPlayerProfile profile, PickaxeEnchantDefinition def, int level) {
        int masteryLevel = enchantService.masteryOf(profile, def.id());
        BigInteger cost = enchantService.masteryCost(def, masteryLevel);
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.buttonName("<aqua>", "MASTERY"));
        MenuLore.button("mastery", List.of(
                "Mastering grants a 1.6x boost",
                "and a 10% activation chance increase."
        ), "<aqua>", List.of(
                "Level: <white>" + masteryLevel + "</white> <gray>/</gray> <red>5",
                "Cost: <white>" + Formatting.format(cost) + " diamonds"
        ), "Click to Master").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack backButton() {
        ItemBuilder builder = ItemBuilder.of(Material.BARRIER).name(MenuLore.buttonName("<red>", "MAIN MENU"));
        MenuLore.button("navigation", List.of(), "<red>", List.of(), "Click to Go Back").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack blackPane() {
        return ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }
}
