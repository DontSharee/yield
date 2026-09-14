package me.dontshare.yieldspawnnpcs.crate;

import me.dontshare.yieldcore.gui.Gui;
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
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * "The Crate Shop" ("/crates") - buy-and-open-instantly, no separate
 * right-click-to-open step (see {@link CrateService}). Spends Credits
 * directly today; pending a redesign to spend farmed Keys instead (crates
 * found while farming, opened with a separately-farmed key), at which point
 * this stops being a Credits purchase at all. Opened by the Crate spawn NPC.
 * Deliberately NOT part of yield-packs' Store hub - that's the real-money
 * Buycraft/Tebex-style storefront, a different concept from this.
 */
public final class CrateGui {

    private static final int TOTAL_ROWS = 3;
    private static final int[] CRATE_SLOTS = {11, 13, 15};
    private static final int INFO_SLOT = 4;
    private static final int CLOSE_SLOT = 22;
    private static final String ACCENT = "<#55FF55>";

    private final YieldPacks packs;
    private final CrateService crateService;
    private final GuiManager guiManager;

    public CrateGui(YieldPacks packs, CrateService crateService, GuiManager guiManager) {
        this.packs = packs;
        this.crateService = crateService;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        var builder = Gui.builder(TOTAL_ROWS, "Crate Shop");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());

        List<CrateDefinition> crates = new ArrayList<>(crateService.content().values());
        for (int i = 0; i < crates.size() && i < CRATE_SLOTS.length; i++) {
            CrateDefinition crate = crates.get(i);
            builder.item(CRATE_SLOTS[i], buildCrateIcon(profile, crate), (clicker, e) -> attemptBuy(clicker, crate.id()));
        }

        builder.item(INFO_SLOT, buildBalanceIcon(profile));
        // Previously both this and INFO_SLOT resolved to slot 22 (TOTAL_ROWS
        // * 9 - 5 == 22 for a 3-row GUI) - the close button silently
        // overwrote the balance icon, so it never actually rendered. Now on
        // opposite rows.
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private void attemptBuy(Player player, String crateId) {
        CrateService.PurchaseOutcome outcome = crateService.buy(player, crateId);
        switch (outcome.result()) {
            case CANT_AFFORD -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You don't have enough Credits for that.</red>"));
            }
            case UNKNOWN_CRATE -> {
                // Shouldn't happen from a real click - the crate list this GUI itself built is always current.
            }
            case SUCCESS -> {
                CrateRewardEntry reward = outcome.reward();
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                player.sendMessage(Text.parse("<green>Crate opened! You got <reward>.</green>",
                        Placeholder.unparsed("reward", describeReward(reward))));
                open(player);
            }
        }
    }

    private String describeReward(CrateRewardEntry reward) {
        return switch (reward.type()) {
            case FLAT_COINS -> "&6" + Formatting.format((double) reward.amount()) + " coins&r";
            case FLAT_DIAMONDS -> "&b" + Formatting.format((double) reward.amount()) + " diamonds&r";
            case PET -> "&da new pet&r";
            case COMMANDS -> "&da bonus&r";
        };
    }

    private ItemStack buildCrateIcon(PackPlayerProfile profile, CrateDefinition crate) {
        boolean canAfford = profile.getCredits().compareTo(java.math.BigInteger.valueOf(crate.cost())) >= 0;
        ItemBuilder builder = ItemBuilder.of(crate.icon())
                .name(canAfford ? MenuLore.buttonName(ACCENT, Formatting.stripLeadingColorCodes(crate.displayName()).toUpperCase(Locale.ROOT))
                        : "&7&l" + Formatting.stripLeadingColorCodes(crate.displayName()).toUpperCase(Locale.ROOT));
        MenuLore.purchase(
                "crate",
                List.of(" &7Buy and open instantly -", " &7no need to carry it around."),
                ACCENT,
                List.of("Cost: &d" + Formatting.format((double) crate.cost()) + " Credits"),
                canAfford ? "Click to Buy" : "Not Enough Credits"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildBalanceIcon(PackPlayerProfile profile) {
        ItemBuilder builder = ItemBuilder.of(Material.SUNFLOWER).name(MenuLore.infoName("<#FFD700>", "YOUR CREDITS"));
        MenuLore.info("balance", List.of(), "<#FFD700>", List.of("Credits: &d" + Formatting.format(profile.getCredits())))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
