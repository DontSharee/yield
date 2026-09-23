package me.dontshare.yieldskilltree.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldskilltree.SkillTreeService.BuyResult;
import me.dontshare.yieldskilltree.SkillTreeService;
import me.dontshare.yieldskilltree.data.Currency;
import me.dontshare.yieldskilltree.data.SkillNode;
import me.dontshare.yieldskilltree.data.NodeType;
import me.dontshare.yieldskilltree.data.SkillTree;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The chest-GUI tree, one instance shared by every tree id.
 * <p>
 * Laid out in lanes: every node sits at its configured slot inside the
 * frame, one upgrade type per column, and each lane is labelled by a
 * coloured pane in the top row above it (named after the lane, with what
 * the lane currently gives). The bottom row is the balance, Close and
 * Max Buy. Everything else inside the frame stays empty.
 */
public final class SkillTreeGui {

    private static final String ACCENT = MenuLore.ACCENT;
    private static final int TOTAL_ROWS = 6;
    private static final int BALANCE_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int MAX_BUY_SLOT = 53;
    /** Max node levels a single Max Buy click purchases - a big backlog buying all at once in one pass is what the reference script's own comment calls out as the lag-spike cause. */
    private static final int AUTO_BUY_CAP = 100;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<Map<String, SkillTree>> trees;
    private final SkillTreeService service;
    private final GuiManager guiManager;

    public SkillTreeGui(PlayerDataStore<PackPlayerProfile> store, Supplier<Map<String, SkillTree>> trees,
                         SkillTreeService service, GuiManager guiManager) {
        this.store = store;
        this.trees = trees;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player, String treeId) {
        SkillTree tree = trees.get().get(treeId);
        if (tree == null) {
            player.sendMessage(Text.parse("<red>That skill tree doesn't exist.</red>"));
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        BigInteger balance = tree.currency().balanceOf(profile);

        var builder = Gui.builder(TOTAL_ROWS, treeTitle(treeId));
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());

        for (Map.Entry<Integer, List<SkillNode>> lane : lanes(tree).entrySet()) {
            builder.item(lane.getKey(), buildLaneHeader(profile, lane.getValue()));
        }
        for (SkillNode node : tree.nodes().values()) {
            builder.item(node.slot(), buildNodeIcon(profile, tree, node, balance),
                    (clicker, event) -> attemptBuy(clicker, treeId, node.id()));
        }

        builder.item(BALANCE_SLOT, buildBalanceIcon(tree, balance));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(MAX_BUY_SLOT, buildMaxBuyIcon(), (clicker, event) -> attemptMaxBuy(clicker, treeId));
        guiManager.open(player, builder.build());
    }

    /**
     * Top-row header slot -> the lane's nodes, top to bottom. A lane is a
     * column with a node in the first row inside the frame; a node that
     * sits alone further down (a capstone between two lanes) has no header.
     */
    private Map<Integer, List<SkillNode>> lanes(SkillTree tree) {
        Map<Integer, List<SkillNode>> lanes = new TreeMap<>();
        for (SkillNode node : tree.nodes().values()) {
            if (node.slot() / 9 == 1) {
                lanes.put(node.slot() % 9, new ArrayList<>());
            }
        }
        for (SkillNode node : tree.nodes().values()) {
            List<SkillNode> lane = lanes.get(node.slot() % 9);
            if (lane != null && node.slot() / 9 >= 1 && node.slot() / 9 <= 4) {
                lane.add(node);
            }
        }
        lanes.values().forEach(lane -> lane.sort(Comparator.comparingInt(SkillNode::slot)));
        return lanes;
    }

    private String treeTitle(String treeId) {
        return switch (treeId) {
            case "upgrades" -> "Upgrades";
            case "prestige_upgrades" -> "Prestige Upgrades";
            default -> treeId;
        };
    }

    private void attemptBuy(Player player, String treeId, String nodeId) {
        BuyResult result = service.buyLevel(player, treeId, nodeId);
        switch (result) {
            case BOUGHT -> player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            case MAXED -> player.sendMessage(Text.parse("<gray>This upgrade is already maxed.</gray>"));
            case LOCKED -> player.sendMessage(Text.parse("<red>Unlock what it requires first.</red>"));
            case NO_FUNDS -> player.sendMessage(Text.parse("<red>You can't afford this yet.</red>"));
            case UNKNOWN -> player.sendMessage(Text.parse("<red>That upgrade doesn't exist.</red>"));
        }
        if (result != BuyResult.BOUGHT) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
        open(player, treeId);
    }

    private void attemptMaxBuy(Player player, String treeId) {
        int bought = service.autoBuy(player, treeId, AUTO_BUY_CAP);
        if (bought == 0) {
            player.sendMessage(Text.parse("<red>Nothing to buy right now.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            player.sendMessage(Text.parse("<green>Bought <count> level<s>!</green>" + (bought >= AUTO_BUY_CAP ? " <gray>(capped - click again for more)</gray>" : ""),
                    Placeholder.unparsed("count", String.valueOf(bought)),
                    Placeholder.unparsed("s", bought == 1 ? "" : "s")));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        }
        open(player, treeId);
    }

    // ---- icons ----

    private ItemStack buildLaneHeader(PackPlayerProfile profile, List<SkillNode> lane) {
        NodeType type = lane.get(0).type();
        double total = 0;
        int owned = 0;
        int max = 0;
        for (SkillNode node : lane) {
            int level = service.levelOf(profile, node.id());
            if (level > 0) {
                total += service.valueFor(node, level);
            }
            owned += level;
            max += node.maxLevel();
        }
        ItemBuilder builder = ItemBuilder.of(paneFor(type)).name(MenuLore.infoName(colorFor(type), laneName(type) + " Lane"));
        MenuLore.info("skill lane", List.of(), colorFor(type), List.of(
                "Levels: " + MenuLore.progress(owned, max),
                "Gives: " + formatValue(type, total)
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildBalanceIcon(SkillTree tree, BigInteger balance) {
        boolean isPrestige = tree.currency() == Currency.PRESTIGE_POINTS;
        ItemBuilder builder = ItemBuilder.of(isPrestige ? Material.NETHER_STAR : Material.SUNFLOWER)
                .name(MenuLore.infoName(isPrestige ? "&d" : "&6", isPrestige ? "Prestige Points" : "Your Coins"));
        MenuLore.info("wallet", List.of(), ACCENT, List.of("Balance: " + (isPrestige ? "&d" : "&6") + Formatting.format(balance)))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildMaxBuyIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.buttonName("&a", "Max Buy"));
        MenuLore.purchase("utility", List.of("Buys every level you can afford,", "finishing what you own first."),
                "&a", List.of(), "Click to buy max").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildNodeIcon(PackPlayerProfile profile, SkillTree tree, SkillNode node, BigInteger balance) {
        boolean unlocked = service.isUnlocked(profile, node);
        int level = service.levelOf(profile, node.id());
        boolean maxed = level >= node.maxLevel();
        String color = colorFor(node.type());
        String category = tree.currency() == Currency.PRESTIGE_POINTS ? "prestige upgrade" : "upgrade";
        List<String> description = List.of(describe(node.type()));

        if (!unlocked) {
            ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE)
                    .name(MenuLore.name("&7", Formatting.stripLeadingColorCodes(node.displayName())));
            List<String> data = new ArrayList<>();
            for (String requiredId : node.requires()) {
                SkillNode required = tree.nodes().get(requiredId);
                String name = required != null ? Formatting.stripLeadingColorCodes(required.displayName()) : requiredId;
                data.add("Requires: " + (service.levelOf(profile, requiredId) >= 1 ? "&a" : "&c") + name);
            }
            MenuLore.info(category, description, "&7", data).forEach(builder::lore);
            return builder.hideAttributes().build();
        }

        ItemBuilder builder = ItemBuilder.of(node.material())
                .name(MenuLore.name(color, Formatting.stripLeadingColorCodes(node.displayName()))
                        + " &7[" + Formatting.toRoman(Math.max(1, level)) + "]");
        List<String> data = new ArrayList<>();
        data.add("Level: " + MenuLore.progress(level, node.maxLevel()));
        data.add("Current: " + formatValue(node.type(), level > 0 ? service.valueFor(node, level) : 0));
        if (maxed) {
            data.add("Status: &aMaxed");
            MenuLore.info(category, description, color, data).forEach(builder::lore);
            builder.enchant(Enchantment.UNBREAKING, 1);
            return builder.hideAttributes().build();
        }
        BigInteger cost = BigDecimal.valueOf(service.costFor(node, level + 1)).setScale(0, RoundingMode.CEILING).toBigInteger();
        boolean affordable = balance.compareTo(cost) >= 0;
        data.add("Next: " + formatValue(node.type(), service.valueFor(node, level + 1)));
        data.add("Cost: " + (affordable ? "&a" : "&c") + Formatting.format(cost) + " &7"
                + (tree.currency() == Currency.PRESTIGE_POINTS ? "Prestige Points" : "coins"));
        if (affordable) {
            MenuLore.button(category, description, color, data, "Click to upgrade").forEach(builder::lore);
        } else {
            MenuLore.info(category, description, color, data).forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    // ---- per-type wording ----

    private static String laneName(NodeType type) {
        return switch (type) {
            case DAMAGE_MULTIPLIER -> "Damage";
            case COIN_MULTIPLIER -> "Coins";
            case LUCK_MULTIPLIER -> "Luck";
            case ROLL_SPEED_MULTIPLIER -> "Hatch Speed";
            case EQUIP_SLOTS -> "Pet Slots";
        };
    }

    private static String describe(NodeType type) {
        return switch (type) {
            case DAMAGE_MULTIPLIER -> "Increases your pets' damage.";
            case COIN_MULTIPLIER -> "Increases the coins you earn.";
            case LUCK_MULTIPLIER -> "Increases your hatch luck.";
            case ROLL_SPEED_MULTIPLIER -> "Hatch eggs faster.";
            case EQUIP_SLOTS -> "Equip more pets at once.";
        };
    }

    private static String formatValue(NodeType type, double value) {
        return switch (type) {
            case EQUIP_SLOTS -> "&a+" + Math.round(value) + " &7pet slot" + (Math.round(value) == 1 ? "" : "s");
            default -> colorFor(type) + "+" + Formatting.format(value * 100) + "% &7" + laneName(type).toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String colorFor(NodeType type) {
        return switch (type) {
            case DAMAGE_MULTIPLIER -> "&c";
            case COIN_MULTIPLIER -> "&6";
            case LUCK_MULTIPLIER -> "&a";
            case ROLL_SPEED_MULTIPLIER -> "&b";
            case EQUIP_SLOTS -> "&d";
        };
    }

    private static Material paneFor(NodeType type) {
        return switch (type) {
            case DAMAGE_MULTIPLIER -> Material.RED_STAINED_GLASS_PANE;
            case COIN_MULTIPLIER -> Material.YELLOW_STAINED_GLASS_PANE;
            case LUCK_MULTIPLIER -> Material.LIME_STAINED_GLASS_PANE;
            case ROLL_SPEED_MULTIPLIER -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case EQUIP_SLOTS -> Material.MAGENTA_STAINED_GLASS_PANE;
        };
    }
}
