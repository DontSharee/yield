package me.dontshare.yieldachievements;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldachievements.command.AchievementsCommand;
import me.dontshare.yieldachievements.command.BuyCommand;
import me.dontshare.yieldachievements.command.MilestonesCommand;
import me.dontshare.yieldachievements.command.PotionsCommand;
import me.dontshare.yieldachievements.data.AchievementContentLoader;
import me.dontshare.yieldachievements.data.AchievementDefinition;
import me.dontshare.yieldachievements.data.MilestoneCategory;
import me.dontshare.yieldachievements.data.MilestoneContentLoader;
import me.dontshare.yieldachievements.gui.AchievementsGui;
import me.dontshare.yieldachievements.gui.MilestoneCategoryGui;
import me.dontshare.yieldachievements.gui.MilestonesGui;
import me.dontshare.yieldachievements.gui.PotionsGui;
import me.dontshare.yieldachievements.gui.StoreGui;
import me.dontshare.yieldachievements.listener.ProgressEventListener;
import me.dontshare.yieldachievements.potion.PotionConsumeListener;
import me.dontshare.yieldachievements.potion.PotionDefinition;
import me.dontshare.yieldachievements.potion.PotionItem;
import me.dontshare.yieldachievements.potion.PotionService;
import me.dontshare.yieldachievements.potion.PotionStat;
import me.dontshare.yieldachievements.store.StoreContentLoader;
import me.dontshare.yieldachievements.store.StoreProduct;
import me.dontshare.yieldachievements.store.StoreService;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.store.StoreCategory;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public final class YieldAchievements extends JavaPlugin {

    private static final String PROVIDER_KEY = "potions";

    private AchievementContentLoader achievementContentLoader;
    private volatile Map<String, AchievementDefinition> achievements;
    private MilestoneContentLoader milestoneContentLoader;
    private volatile Map<String, MilestoneCategory> milestoneCategories;
    private StoreContentLoader storeContentLoader;
    private volatile Map<String, StoreProduct> storeProducts;
    private PotionItem potionItem;

    @Override
    public void onEnable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        achievementContentLoader = new AchievementContentLoader(this, getLogger());
        achievements = achievementContentLoader.load();
        milestoneContentLoader = new MilestoneContentLoader(this, getLogger());
        milestoneCategories = milestoneContentLoader.load();
        storeContentLoader = new StoreContentLoader(this, getLogger());
        storeProducts = storeContentLoader.load();

        potionItem = new PotionItem(this);
        PotionService potionService = new PotionService(packs.getPlayerStore());
        packs.registerCoinMultiplierProvider(PROVIDER_KEY, profile -> potionService.multiplierFor(profile, PotionStat.COINS));
        packs.registerDamageMultiplierProvider(PROVIDER_KEY, profile -> potionService.multiplierFor(profile, PotionStat.DAMAGE));
        packs.getPackOpenService().registerCooldownMultiplierProvider(PROVIDER_KEY, profile -> potionService.multiplierFor(profile, PotionStat.ROLL_SPEED));
        // LuckService sums additive bonuses rather than multiplying factors
        // (see its own extraLuckProviders) - "-1.0" converts a potion's
        // multiplicative factor (e.g. 2.0 = double luck) into the additive
        // bonus that system expects (+1.0), so "2x Luck" means the same
        // real thing here as it does for the product-based stats above.
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY, profile -> potionService.multiplierFor(profile, PotionStat.LUCK) - 1.0);
        core.getListenerManager().register(new PotionConsumeListener(potionItem, potionService));

        AchievementService achievementService = new AchievementService(() -> achievements, packs.getPlayerStore());
        MilestoneService milestoneService = new MilestoneService(() -> milestoneCategories, packs.getPlayerStore(), potionItem);
        StoreService storeService = new StoreService(() -> storeProducts, packs.getPlayerStore());

        AchievementsGui achievementsGui = new AchievementsGui(() -> achievements, packs.getPlayerStore(), achievementService, core.getGuiManager());
        MilestoneCategoryGui categoryGui = new MilestoneCategoryGui(() -> milestoneCategories, packs.getPlayerStore(), milestoneService, core.getGuiManager());
        MilestonesGui milestonesGui = new MilestonesGui(() -> milestoneCategories, packs.getPlayerStore(), milestoneService, core.getGuiManager(), categoryGui);
        categoryGui.setHubGui(milestonesGui);
        StoreGui storeGui = new StoreGui(() -> storeProducts, packs.getPlayerStore(), storeService, core.getGuiManager());
        PotionsGui potionsGui = new PotionsGui(packs.getPlayerStore(), potionService, core.getGuiManager());

        core.getListenerManager().register(new ProgressEventListener(achievementService, milestoneService));

        CommandManager.register(this, AchievementsCommand.build(achievementsGui), "View your achievements");
        CommandManager.register(this, MilestonesCommand.build(milestonesGui), "View your milestone progress");
        // "/buy" opens the SHARED Store hub (yield-packs), not this plugin's
        // own StoreGui directly - the Credits Store is one category button
        // among Rankup/Crates/the Pack Shop there, not its own top-level menu.
        packs.registerStoreCategory(new StoreCategory("store", 10, YieldAchievements::storeCategoryIcon, storeGui::renderInto));
        CommandManager.register(this, BuyCommand.build(packs.getStoreHubGui()), "Open the Store - Rankup, the Credits Store, Crates and more");
        CommandManager.register(this, PotionsCommand.build(potionsGui), "View your active potions");

        core.getAdminCommandRegistry().register(buildAchievementsAdminCommand(achievementService));
        core.getAdminCommandRegistry().register(buildMilestonesAdminCommand(milestoneService));
        core.getAdminCommandRegistry().register(buildPotionsAdminCommand());
    }

    private static ItemStack storeCategoryIcon(boolean selected) {
        ItemBuilder builder = ItemBuilder.of(Material.AMETHYST_SHARD).name(MenuLore.buttonName("<#FFD700>", "STORE"));
        MenuLore.button("store", List.of(" &7Spend Credits on donor ranks,", " &7gamepasses, and potions."),
                "<#FFD700>", selected ? "Selected" : "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private LiteralCommandNode<CommandSourceStack> buildAchievementsAdminCommand(AchievementService achievementService) {
        return Commands.literal("achievements")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            achievements = achievementContentLoader.load();
                            milestoneCategories = milestoneContentLoader.load();
                            storeProducts = storeContentLoader.load();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>Reloaded " + achievements.size()
                                    + " achievement(s), " + milestoneCategories.size() + " milestone categor(y/ies), " + storeProducts.size()
                                    + " store product(s) (achievements/milestones/store share one config-load pass).</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("achievement", StringArgumentType.word())
                                        .executes(ctx -> {
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                                            Player target = resolver.resolve(ctx.getSource()).getFirst();
                                            String id = StringArgumentType.getString(ctx, "achievement");
                                            AchievementService.UnlockResult result = achievementService.forceComplete(target, id);
                                            switch (result) {
                                                case SUCCESS -> ctx.getSource().getSender().sendMessage(Text.parse("<green>Unlocked '" + id + "' for " + target.getName() + ".</green>"));
                                                case ALREADY_COMPLETE -> ctx.getSource().getSender().sendMessage(Text.parse("<red>" + target.getName() + " already has '" + id + "'.</red>"));
                                                case UNKNOWN -> ctx.getSource().getSender().sendMessage(Text.parse("<red>No such achievement '" + id + "'.</red>"));
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> buildMilestonesAdminCommand(MilestoneService milestoneService) {
        return Commands.literal("milestones")
                .then(Commands.literal("unlock")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("tier", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                                                    Player target = resolver.resolve(ctx.getSource()).getFirst();
                                                    String category = StringArgumentType.getString(ctx, "category");
                                                    // Players see tiers numbered from 1 (see MilestoneCategoryGui) - the service itself is 0-indexed.
                                                    int tierIndex = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "tier") - 1;
                                                    MilestoneService.ClaimResult result = milestoneService.forceClaim(target, category, tierIndex);
                                                    switch (result) {
                                                        case SUCCESS -> ctx.getSource().getSender().sendMessage(Text.parse("<green>Unlocked tier " + (tierIndex + 1) + " of '" + category + "' for " + target.getName() + ".</green>"));
                                                        case ALREADY_CLAIMED -> ctx.getSource().getSender().sendMessage(Text.parse("<red>" + target.getName() + " already claimed that tier.</red>"));
                                                        default -> ctx.getSource().getSender().sendMessage(Text.parse("<red>No such category/tier '" + category + "' " + (tierIndex + 1) + ".</red>"));
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                .build();
    }

    /** "/admin potions give <player> <stat> <multiplier> <duration>" - builds the "POTION_<STAT>_<MULTIPLIER>_<DURATION>" id under the hood so an admin never has to type that format by hand. Also what store.yml's product "commands" call to actually hand over a sold potion. */
    private LiteralCommandNode<CommandSourceStack> buildPotionsAdminCommand() {
        return Commands.literal("potions")
                .then(Commands.literal("give")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("stat", StringArgumentType.word())
                                        .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01))
                                                .then(Commands.argument("duration", LongArgumentType.longArg(1))
                                                        .executes(this::adminGivePotion))))))
                .build();
    }

    private int adminGivePotion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String stat = StringArgumentType.getString(ctx, "stat").toUpperCase(java.util.Locale.ROOT);
        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        long duration = LongArgumentType.getLong(ctx, "duration");

        PotionDefinition def = PotionDefinition.parse("POTION_" + stat + "_" + multiplier + "_" + duration);
        if (def == null) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid stat '" + stat + "' - expected COINS, DAMAGE, LUCK, or ROLL_SPEED.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        target.getInventory().addItem(potionItem.create(def));
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + target.getName() + " a x" + multiplier + " " + stat + " potion (" + duration + "s).</green>"));
        return Command.SINGLE_SUCCESS;
    }
}
