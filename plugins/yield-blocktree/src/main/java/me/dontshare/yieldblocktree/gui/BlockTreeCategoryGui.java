package me.dontshare.yieldblocktree.gui;

import me.dontshare.yieldblocktree.BlockTreeService;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeEffect;
import me.dontshare.yieldblocktree.data.BlockTreeTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
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
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * One block's 7-tier ladder - unlike yield-achievements' MilestoneCategoryGui
 * (an open-ended, paginated list), a block always has exactly 7 tiers, so
 * this is a single, non-paginated row: red = locked, yellow = in progress,
 * lime = complete (glowing if not yet claimed - click to claim, plain once
 * it is).
 */
public final class BlockTreeCategoryGui {

    private static final int TOTAL_ROWS = 6;
    private static final int BACK_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int TIER_START_SLOT = 10;

    private final Supplier<Map<Material, BlockTreeDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final BlockTreeService service;
    private final GuiManager guiManager;
    private BlockTreeGui hubGui;

    public BlockTreeCategoryGui(Supplier<Map<Material, BlockTreeDefinition>> content, PlayerDataStore<PackPlayerProfile> store,
                                 BlockTreeService service, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    /** Breaks the constructor cycle with {@link BlockTreeGui} - same setter-injection idiom as MilestoneCategoryGui/MilestonesGui. */
    public void setHubGui(BlockTreeGui hubGui) {
        this.hubGui = hubGui;
    }

    public void open(Player player, Material material) {
        BlockTreeDefinition def = content.get().get(material);
        if (def == null) {
            player.sendMessage(Text.parse("<red>That block's tree no longer exists.</red>"));
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<BlockTreeTier> tiers = def.tiers();

        var builder = Gui.builder(TOTAL_ROWS, Formatting.stripLeadingColorCodes(def.displayName()));
        for (int i = 0; i < tiers.size() && i < 7; i++) {
            int tierIndex = i;
            builder.item(TIER_START_SLOT + i, buildTierIcon(profile, material, def, tiers.get(i), tierIndex),
                    (clicker, e) -> handleClick(clicker, material, tierIndex));
        }
        builder.fill(IntStream.range(45, 54).filter(s -> s != BACK_SLOT && s != CLOSE_SLOT), GuiIcons.filler());
        builder.item(BACK_SLOT, buildBackButton(), (clicker, e) -> {
            if (hubGui != null) {
                hubGui.open(clicker);
            }
        });
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private void handleClick(Player player, Material material, int tierIndex) {
        BlockTreeDefinition def = content.get().get(material);
        if (def == null || tierIndex >= def.tiers().size()) {
            return;
        }
        BlockTreeTier tier = def.tiers().get(tierIndex);
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        BlockTreeService.TierState state = profile != null ? service.stateOf(profile, material, tierIndex, tier) : BlockTreeService.TierState.INCOMPLETE;

        switch (state) {
            case COMPLETE_UNCLAIMED -> {
                BlockTreeService.ClaimResult result = service.claim(player, material, tierIndex);
                if (result == BlockTreeService.ClaimResult.SUCCESS) {
                    player.sendMessage(Text.parse("<green><bold>Blocktree tier claimed!</bold></green> <gray>"
                            + Formatting.stripLeadingColorCodes(def.displayName()) + " Tier " + (tierIndex + 1) + "</gray>"));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }
            }
            case CLAIMED -> player.sendMessage(Text.parse("<gray>Already claimed.</gray>"));
            case INCOMPLETE, IN_PROGRESS -> {
                BlockTreeService.ClaimResult result = service.claim(player, material, tierIndex);
                if (result == BlockTreeService.ClaimResult.LOCKED) {
                    player.sendMessage(Text.parse("<red>Claim the previous tier first.</red>"));
                } else {
                    player.sendMessage(Text.parse("<red>Not complete yet.</red>"));
                }
            }
        }
        open(player, material);
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ARROW).name(MenuLore.buttonName(MenuLore.ACCENT, "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the block", " &7list."), MenuLore.ACCENT, "Click to Go Back")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildTierIcon(PackPlayerProfile profile, Material material, BlockTreeDefinition def, BlockTreeTier tier, int tierIndex) {
        BlockTreeService.TierState state = service.stateOf(profile, material, tierIndex, tier);
        long progress = Math.min(tier.goal(), service.progressOf(profile, material));

        Material icon = switch (state) {
            case INCOMPLETE -> Material.RED_STAINED_GLASS_PANE;
            case IN_PROGRESS -> Material.YELLOW_STAINED_GLASS_PANE;
            case COMPLETE_UNCLAIMED, CLAIMED -> Material.LIME_STAINED_GLASS_PANE;
        };
        String stateLabel = switch (state) {
            case INCOMPLETE -> "&cLocked";
            case IN_PROGRESS -> "&eIn Progress";
            case COMPLETE_UNCLAIMED -> "&a&lReady to Claim!";
            case CLAIMED -> "&7Claimed";
        };

        ItemBuilder builder = ItemBuilder.of(icon).name("&f" + Formatting.stripLeadingColorCodes(def.displayName()) + " &8- &fTier " + (tierIndex + 1));
        List<String> data = new ArrayList<>();
        data.add("Goal: &f" + Formatting.format((double) tier.goal()));
        data.add("Progress: &f" + Formatting.format((double) progress) + " &7/ &f" + Formatting.format((double) tier.goal()));
        data.add("");
        for (BlockTreeEffect effect : tier.effects()) {
            data.add(describe(effect, def));
        }
        MenuLore.info("blocktree", List.of(), MenuLore.ACCENT, data).forEach(builder::lore);
        builder.lore("").lore(stateLabel);
        if (state == BlockTreeService.TierState.COMPLETE_UNCLAIMED) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    /** A plain-English line generated from the effect's own type/value - never hand-written per tier, so blocktree.yml stays the single source of truth. */
    private String describe(BlockTreeEffect effect, BlockTreeDefinition def) {
        String blockName = Formatting.stripLeadingColorCodes(def.displayName());
        String percent = Formatting.format(effect.value() * 100) + "%";
        return switch (effect.type()) {
            case FLAT_COINS -> "&6+" + Formatting.format(effect.value()) + " Coins &7per " + blockName;
            case FLAT_DIAMONDS -> "&b+" + Formatting.format(effect.value()) + " Diamonds &7per " + blockName;
            case FLAT_CREDITS -> "&d+" + Formatting.format(effect.value()) + " Credits &7per " + blockName;
            case BLOCK_COIN_MULTIPLIER -> "&6+" + percent + " Coins &7from " + blockName;
            case BLOCK_DIAMOND_MULTIPLIER -> "&b+" + percent + " Diamonds &7from " + blockName;
            case GLOBAL_COIN_MULTIPLIER -> "&6+" + percent + " Coins &7from everything";
            case GLOBAL_DAMAGE_MULTIPLIER -> "&c+" + percent + " Damage &7from everything";
            case GLOBAL_LUCK_BOOST -> "&d+" + percent + " Luck &7everywhere";
            case GLOBAL_ATTACK_SPEED_MULTIPLIER -> "&e+" + percent + " Attack Speed &7for all pets";
            case ROLL_SPEED_MULTIPLIER -> "&a+" + percent + " Pack Opening Speed";
            case DOUBLE_HIT_CHANCE -> "&e" + percent + " &7chance to double-hit with pets";
            case TRIPLE_HIT_CHANCE -> "&6" + percent + " &7chance to triple-hit with pets";
            case EXCLUSIVE_FIND_CHANCE -> "&b" + percent + " &7chance to find an Exclusive in any pack";
            case DIAMOND_CHANCE_BOOST -> "&b+" + percent + " Diamond Find Chance &7from cubes";
            case PROGRESS_MULTIPLIER -> "&d+" + percent + " Blocktree Progress &7from every block";
            case UNLOCK_SHARD_DROP -> "&5Unlocks " + percent + " chance for " + blockName + " &7to drop a Shard";
        };
    }
}
