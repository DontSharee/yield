package me.dontshare.yieldblocktree.gui;

import me.dontshare.yieldblocktree.BlockTreeService;
import me.dontshare.yieldblocktree.data.BlockPerk;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeEffect;
import me.dontshare.yieldblocktree.data.BlockTreeTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
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

/**
 * One block's 7-tier ladder, three rows tall: back in the corner and the
 * block itself in the middle of the top row, its seven tiers centred
 * across the middle row, close underneath.
 * Red = locked, yellow = in progress, lime = complete (glowing if not yet
 * claimed - click to claim). The seventh tier is the tree's one-of-a-kind
 * reward and gets a nether star and its own name instead of a pane.
 */
public final class BlockTreeCategoryGui {

    private static final int TOTAL_ROWS = 3;
    private static final int HEADER_SLOT = 4;
    private static final int TIER_ROW = 1;
    private static final int BACK_SLOT = 0;
    private static final int CLOSE_SLOT = 22;

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
        builder.fill(GuiLayout.all(TOTAL_ROWS), GuiIcons.filler());
        builder.item(HEADER_SLOT, buildHeader(profile, material, def));
        // Centred whatever the count: seven fill the middle row, fewer sit in the middle of it.
        int[] slots = GuiLayout.centeredRow(TIER_ROW, Math.min(GuiLayout.INNER_WIDTH, tiers.size()));
        for (int i = 0; i < slots.length; i++) {
            int tierIndex = i;
            builder.item(slots[i], buildTierIcon(profile, material, def, tiers.get(i), tierIndex),
                    (clicker, e) -> handleClick(clicker, material, tierIndex));
        }
        builder.item(BACK_SLOT, GuiIcons.backButton("the block list"), (clicker, e) -> {
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
                    boolean top = tierIndex == def.tiers().size() - 1 && def.perkTitle() != null;
                    player.sendMessage(Text.parse(top
                            ? "<green><bold>Blocktree complete!</bold></green> <gray>Unlocked</gray> " + def.perkTitle()
                            : "<green><bold>Blocktree tier claimed!</bold></green> <gray>"
                                    + Formatting.stripLeadingColorCodes(def.displayName()) + " Tier " + (tierIndex + 1) + "</gray>"));
                    player.playSound(player.getLocation(), top ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
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

    private ItemStack buildHeader(PackPlayerProfile profile, Material material, BlockTreeDefinition def) {
        int claimed = 0;
        for (int i = 0; i < def.tiers().size(); i++) {
            if (service.stateOf(profile, material, i, def.tiers().get(i)) == BlockTreeService.TierState.CLAIMED) {
                claimed++;
            }
        }
        ItemBuilder builder = ItemBuilder.of(def.icon()).name(MenuLore.infoName(MenuLore.ACCENT,
                Formatting.stripLeadingColorCodes(def.displayName()).toUpperCase(java.util.Locale.ROOT)));
        List<String> data = new ArrayList<>();
        data.add("Broken: &f" + Formatting.format((double) service.progressOf(profile, material)));
        data.add("Tiers: " + MenuLore.progress(claimed, def.tiers().size()));
        if (def.perkTitle() != null) {
            data.add("Top Reward: " + def.perkTitle());
        }
        MenuLore.info("blocktree", List.of(), MenuLore.ACCENT, data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildTierIcon(PackPlayerProfile profile, Material material, BlockTreeDefinition def, BlockTreeTier tier, int tierIndex) {
        BlockTreeService.TierState state = service.stateOf(profile, material, tierIndex, tier);
        long progress = Math.min(tier.goal(), service.progressOf(profile, material));
        boolean top = tierIndex == def.tiers().size() - 1 && def.perkTitle() != null;

        Material icon = top ? Material.NETHER_STAR : switch (state) {
            case INCOMPLETE -> Material.RED_STAINED_GLASS_PANE;
            case IN_PROGRESS -> Material.YELLOW_STAINED_GLASS_PANE;
            case COMPLETE_UNCLAIMED, CLAIMED -> Material.LIME_STAINED_GLASS_PANE;
        };
        String stateLabel = switch (state) {
            case INCOMPLETE -> "&cLocked";
            case IN_PROGRESS -> "&eIn Progress";
            case COMPLETE_UNCLAIMED -> "&aReady to claim";
            case CLAIMED -> "&aClaimed";
        };

        String tierTag = " &7[" + Formatting.toRoman(tierIndex + 1) + "]";
        String name = top
                ? def.perkTitle() + tierTag
                : MenuLore.name("&f", Formatting.stripLeadingColorCodes(def.displayName())) + tierTag;
        ItemBuilder builder = ItemBuilder.of(icon).name(name);
        List<String> data = new ArrayList<>();
        data.add("Goal: &f" + Formatting.format((double) tier.goal()));
        data.add("Progress: " + MenuLore.progress(progress, tier.goal()));
        data.add("Status: " + stateLabel);
        data.add("");
        for (BlockTreeEffect effect : tier.effects()) {
            data.add(describe(effect, def));
        }
        if (state == BlockTreeService.TierState.COMPLETE_UNCLAIMED) {
            MenuLore.button("blocktree tier", List.of(), MenuLore.ACCENT, data, "Click to claim").forEach(builder::lore);
        } else {
            MenuLore.info("blocktree tier", List.of(), MenuLore.ACCENT, data).forEach(builder::lore);
        }
        if (state == BlockTreeService.TierState.COMPLETE_UNCLAIMED || (top && state == BlockTreeService.TierState.CLAIMED)) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    /** A plain-English line generated from the effect's own type/value - never hand-written per tier, so blocktree.yml stays the single source of truth. */
    static String describe(BlockTreeEffect effect, BlockTreeDefinition def) {
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
            case ROLL_SPEED_MULTIPLIER -> "&a+" + percent + " Hatch Speed";
            case DOUBLE_HIT_CHANCE -> "&e" + percent + " &7chance to double-hit with pets";
            case TRIPLE_HIT_CHANCE -> "&6" + percent + " &7chance to triple-hit with pets";
            case EXCLUSIVE_FIND_CHANCE -> "&b" + percent + " &7chance to find an Exclusive in any egg";
            case DIAMOND_CHANCE_BOOST -> "&b+" + percent + " Diamond Find Chance &7from cubes";
            case PROGRESS_MULTIPLIER -> "&d+" + percent + " Blocktree Progress &7from every block";
            case UNLOCK_SHARD_DROP -> "&e" + percent + " &7chance for " + blockName + " &7to drop a &5Shard";
            case PERK -> BlockPerk.valueOf(effect.data()).describe(effect.value());
        };
    }
}
