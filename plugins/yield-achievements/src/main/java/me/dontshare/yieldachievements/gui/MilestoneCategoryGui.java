package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.MilestoneService;
import me.dontshare.yieldachievements.data.MilestoneCategory;
import me.dontshare.yieldachievements.data.MilestoneTier;
import me.dontshare.yieldachievements.potion.PotionDefinition;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * One category's paginated tier ladder - a 6-row chest, each tier a colored
 * glass pane: red = no progress, yellow = in progress, lime = complete
 * (glowing if not yet claimed - click to claim, plain once it is).
 */
public final class MilestoneCategoryGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_ROWS = TOTAL_ROWS - 1;
    /** Centred rows of seven inside the border - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(CONTENT_ROWS);
    private static final int PREV_SLOT = 47;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final Supplier<Map<String, MilestoneCategory>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final MilestoneService service;
    private final GuiManager guiManager;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    private MilestonesGui hubGui;

    public MilestoneCategoryGui(Supplier<Map<String, MilestoneCategory>> content, PlayerDataStore<PackPlayerProfile> store,
                                 MilestoneService service, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    /** Breaks the constructor cycle with {@link MilestonesGui} (which itself needs this class for its category click handlers) - same setter-injection idiom as ZoneLockService/ZonePurchaseGui. */
    public void setHubGui(MilestonesGui hubGui) {
        this.hubGui = hubGui;
    }

    public void open(Player player, String categoryId) {
        MilestoneCategory category = content.get().get(categoryId);
        if (category == null) {
            player.sendMessage(Text.parse("<red>That milestone category no longer exists.</red>"));
            return;
        }
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(id);
        List<MilestoneTier> tiers = category.tiers();
        Page<MilestoneTier> page = Page.of(tiers, pageIndex.getOrDefault(id, 0), PAGE_SIZE);
        pageIndex.put(id, page.index());
        int baseIndex = page.index() * PAGE_SIZE;

        var builder = Gui.builder(TOTAL_ROWS, category.displayName());
        List<MilestoneTier> items = page.items();
        builder.fill(IntStream.range(0, 45), GuiIcons.filler());
        int[] contentSlots = GuiLayout.centered(0, items.size());
        for (int i = 0; i < items.size(); i++) {
            int tierIndex = baseIndex + i;
            MilestoneTier tier = items.get(i);
            builder.item(contentSlots[i], buildTierIcon(profile, category, tier, tierIndex), (clicker, e) -> handleClick(clicker, categoryId, tierIndex));
        }
        builder.fill(IntStream.range(45, 54).filter(s -> s != PREV_SLOT && s != BACK_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, categoryId, -1));
        builder.item(BACK_SLOT, buildBackButton(), (clicker, e) -> {
            if (hubGui != null) {
                hubGui.open(clicker);
            }
        });
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, categoryId, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, String categoryId, int delta) {
        pageIndex.put(player.getUniqueId(), pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        open(player, categoryId);
    }

    private void handleClick(Player player, String categoryId, int tierIndex) {
        MilestoneCategory category = content.get().get(categoryId);
        if (category == null || tierIndex >= category.tiers().size()) {
            return;
        }
        MilestoneTier tier = category.tiers().get(tierIndex);
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        MilestoneService.TierState state = profile != null ? service.stateOf(profile, categoryId, tierIndex, tier) : MilestoneService.TierState.INCOMPLETE;

        switch (state) {
            case COMPLETE_UNCLAIMED -> {
                MilestoneService.ClaimResult result = service.claim(player, categoryId, tierIndex);
                if (result == MilestoneService.ClaimResult.SUCCESS) {
                    player.sendMessage(Text.parse("<green><bold>Milestone claimed!</bold></green> <gold>+" + Formatting.format(tier.rewardCoins()) + " coins</gold>"
                            + (tier.rewardDiamonds().signum() > 0 ? " <aqua>+" + Formatting.format(tier.rewardDiamonds()) + " diamonds</aqua>" : "")
                            + (tier.rewardCredits().signum() > 0 ? " <yellow>+" + Formatting.format(tier.rewardCredits()) + " credits</yellow>" : "")
                            + (tier.rewardPotionId() != null ? " <#4BD9FF>+1 potion</#4BD9FF>" : "")));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }
            }
            case CLAIMED -> player.sendMessage(Text.parse("<gray>Already claimed.</gray>"));
            case INCOMPLETE, IN_PROGRESS -> {
                MilestoneService.ClaimResult result = service.claim(player, categoryId, tierIndex);
                if (result == MilestoneService.ClaimResult.LOCKED) {
                    player.sendMessage(Text.parse("<red>Claim the previous tier first.</red>"));
                } else {
                    player.sendMessage(Text.parse("<red>Not complete yet.</red>"));
                }
            }
        }
        open(player, categoryId);
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ARROW).name(MenuLore.buttonName("<#4BD9FF>", "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the category", " &7list."), "<#4BD9FF>", "Click to Go Back")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildTierIcon(PackPlayerProfile profile, MilestoneCategory category, MilestoneTier tier, int tierIndex) {
        MilestoneService.TierState state = service.stateOf(profile, category.id(), tierIndex, tier);
        long progress = Math.min(tier.goal(), service.progressOf(profile, category.id()));

        Material material = switch (state) {
            case INCOMPLETE -> Material.RED_STAINED_GLASS_PANE;
            case IN_PROGRESS -> Material.YELLOW_STAINED_GLASS_PANE;
            case COMPLETE_UNCLAIMED, CLAIMED -> Material.LIME_STAINED_GLASS_PANE;
        };
        String stateLabel = switch (state) {
            case INCOMPLETE -> "&cLocked";
            case IN_PROGRESS -> "&eIn Progress";
            case COMPLETE_UNCLAIMED -> "&aReady to claim";
            case CLAIMED -> "&7Claimed";
        };

        ItemBuilder builder = ItemBuilder.of(material).name(MenuLore.name("&f", Formatting.stripLeadingColorCodes(category.displayName())) + " &7[" + Formatting.toRoman(tierIndex + 1) + "]");
        List<String> data = new ArrayList<>();
        data.add("Goal: &f" + Formatting.format((double) tier.goal()));
        data.add("Progress: " + MenuLore.progress(progress, tier.goal()));
        data.add("");
        if (tier.rewardCoins().signum() > 0) {
            data.add("Coins: &6" + Formatting.format(tier.rewardCoins()));
        }
        if (tier.rewardDiamonds().signum() > 0) {
            data.add("Diamonds: &b" + Formatting.format(tier.rewardDiamonds()));
        }
        if (tier.rewardCredits().signum() > 0) {
            data.add("Credits: &e" + Formatting.format(tier.rewardCredits()));
        }
        if (tier.rewardPotionId() != null) {
            PotionDefinition potion = PotionDefinition.parse(tier.rewardPotionId());
            if (potion != null) {
                String multiplierLabel = potion.multiplier() == Math.rint(potion.multiplier())
                        ? String.valueOf((long) potion.multiplier()) : String.valueOf(potion.multiplier());
                data.add("Potion: &fx" + multiplierLabel + " " + potion.stat().name().replace('_', ' '));
            }
        }
        data.add("Status: " + stateLabel);
        if (state == MilestoneService.TierState.COMPLETE_UNCLAIMED) {
            MenuLore.button("milestone", List.of(), MenuLore.ACCENT, data, "Click to claim").forEach(builder::lore);
        } else {
            MenuLore.info("milestone", List.of(), MenuLore.ACCENT, data).forEach(builder::lore);
        }
        if (state == MilestoneService.TierState.COMPLETE_UNCLAIMED) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }
}
