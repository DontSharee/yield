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
import me.dontshare.yieldskilltree.data.SkillTree;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The actual chest-GUI tree, one instance shared by every tree id - mirrors
 * the reference Skript script's Skilltree_openPage, translated to this
 * codebase's existing Gui.builder/GuiIcons idiom (see FusionGui/QuestGui).
 * Nodes render at their configured slot; everything else in rows 2-4 stays
 * genuinely empty, matching the "filler is only for border/control slots"
 * convention used everywhere else in this codebase.
 */
public final class SkillTreeGui {

    private static final String ACCENT = "<#4BD9FF>";
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

        var builder = Gui.builder(TOTAL_ROWS, treeTitle(treeId));
        builder.fill(IntStream.range(0, 9), GuiIcons.filler());
        builder.fill(IntStream.range(45, 54).filter(slot -> slot != BALANCE_SLOT && slot != MAX_BUY_SLOT && slot != CLOSE_SLOT), GuiIcons.filler());

        builder.item(BALANCE_SLOT, buildBalanceIcon(tree, profile));
        builder.item(MAX_BUY_SLOT, buildMaxBuyIcon(), (clicker, event) -> attemptMaxBuy(clicker, treeId));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        for (SkillNode node : tree.nodes().values()) {
            builder.item(node.slot(), buildNodeIcon(profile, node), (clicker, event) -> attemptBuy(clicker, treeId, node.id()));
        }

        guiManager.open(player, builder.build());
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
            case BOUGHT -> player.sendMessage(Text.parse("<green>Upgraded!</green>"));
            case MAXED -> player.sendMessage(Text.parse("<red>This node is already maxed out.</red>"));
            case LOCKED -> player.sendMessage(Text.parse("<red>Unlock its prerequisite(s) first.</red>"));
            case NO_FUNDS -> player.sendMessage(Text.parse("<red>You can't afford this yet.</red>"));
            case UNKNOWN -> player.sendMessage(Text.parse("<red>That node doesn't exist.</red>"));
        }
        open(player, treeId);
    }

    private void attemptMaxBuy(Player player, String treeId) {
        int bought = service.autoBuy(player, treeId, AUTO_BUY_CAP);
        if (bought == 0) {
            player.sendMessage(Text.parse("<red>Nothing to buy right now.</red>"));
        } else if (bought >= AUTO_BUY_CAP) {
            player.sendMessage(Text.parse("<green>Bought <count> level(s)! (capped - click again for more)</green>",
                    Placeholder.unparsed("count", String.valueOf(bought))));
        } else {
            player.sendMessage(Text.parse("<green>Bought <count> level(s)!</green>",
                    Placeholder.unparsed("count", String.valueOf(bought))));
        }
        open(player, treeId);
    }

    private ItemStack buildBalanceIcon(SkillTree tree, PackPlayerProfile profile) {
        boolean isPrestige = tree.currency() == Currency.PRESTIGE_POINTS;
        Material material = isPrestige ? Material.NETHER_STAR : Material.SUNFLOWER;
        String label = isPrestige ? "PRESTIGE POINTS" : "COINS";
        ItemBuilder builder = ItemBuilder.of(material).name(MenuLore.infoName(ACCENT, label));
        MenuLore.info("wallet", List.of(), ACCENT, List.of("Balance: &f" + Formatting.format(tree.currency().balanceOf(profile))))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildMaxBuyIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(MenuLore.name("<#46FF6A>", "Max Buy"));
        MenuLore.button(
                "utility",
                List.of(" &7Buys every level you can afford,", " &7maxing what you already own", " &7before unlocking anything new."),
                ACCENT,
                "Click to Buy All"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildNodeIcon(PackPlayerProfile profile, SkillNode node) {
        boolean unlocked = service.isUnlocked(profile, node);
        int level = service.levelOf(profile, node.id());
        boolean maxed = level >= node.maxLevel();

        if (!unlocked) {
            ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE).name(MenuLore.name("&c", "Locked"));
            return builder.lore("&7Unlock its prerequisite(s) first.").hideAttributes().build();
        }

        ItemBuilder builder = ItemBuilder.of(node.material()).name(node.displayName());
        List<String> data = new ArrayList<>();
        data.add("&7Level: " + MenuLore.progress(level, node.maxLevel()));
        if (maxed) {
            data.add("&a&lMAXED!");
            MenuLore.info("skill", List.of(), ACCENT, data).forEach(builder::lore);
            return builder.hideAttributes().build();
        }

        double cost = service.costFor(node, level + 1);
        double nextValue = service.valueFor(node, level + 1);
        data.add("&7Next value: &a" + Formatting.format(nextValue));
        data.add("&7Cost: &f" + Formatting.format(cost));
        MenuLore.button("skill", data, ACCENT, "Click to Upgrade").forEach(builder::lore);
        builder.enchant(Enchantment.UNBREAKING, 1);
        return builder.hideAttributes().build();
    }
}
