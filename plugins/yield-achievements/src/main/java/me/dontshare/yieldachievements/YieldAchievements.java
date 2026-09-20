package me.dontshare.yieldachievements;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import me.dontshare.yieldachievements.boost.ServerBoost;
import me.dontshare.yieldachievements.donation.DonationGoalService;
import me.dontshare.yieldachievements.donation.DonationGoalStore;
import me.dontshare.yieldachievements.donation.PendingPurchaseListener;
import me.dontshare.yieldachievements.donation.PendingPurchaseStore;
import me.dontshare.yieldachievements.boost.ServerBoostService;
import me.dontshare.yieldachievements.boost.ServerBoostStore;
import me.dontshare.yieldachievements.potion.PotionConsumeListener;
import me.dontshare.yieldachievements.potion.PotionDefinition;
import me.dontshare.yieldachievements.potion.PotionItem;
import me.dontshare.yieldachievements.potion.PotionService;
import me.dontshare.yieldachievements.potion.PotionStat;
import me.dontshare.yieldachievements.store.StoreContentLoader;
import me.dontshare.yieldachievements.store.StoreProduct;
import me.dontshare.yieldachievements.store.StoreProductCategory;
import me.dontshare.yieldachievements.store.StoreService;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldachievements.data.AchievementProfile;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.store.StoreCategory;
import me.dontshare.yieldpacks.store.StoreHubGui;
import org.bukkit.Material;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class YieldAchievements extends JavaPlugin {

    private static final String PROVIDER_KEY = "potions";
    private static final String BOOST_PROVIDER_KEY = "server_boosts";
    private static final String DONATION_PROVIDER_KEY = "donation_goal";

    private AchievementContentLoader achievementContentLoader;
    private volatile Map<String, AchievementDefinition> achievements;
    private MilestoneContentLoader milestoneContentLoader;
    private volatile Map<String, MilestoneCategory> milestoneCategories;
    private StoreContentLoader storeContentLoader;
    private volatile Map<String, StoreProduct> storeProducts;
    private PotionItem potionItem;
    private ServerBoostService serverBoostService;
    private DonationGoalService donationGoalService;
    /** Held as a field only so the Tebex purchase command can reach it - everything else takes what it needs as a parameter. */
    private PlayerDataStore<PackPlayerProfile> packStore;
    private PendingPurchaseStore pendingPurchaseStore;

    @Override
    public void onEnable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        packStore = packs.getPlayerStore();
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        achievementContentLoader = new AchievementContentLoader(this, getLogger());
        achievements = achievementContentLoader.load();
        milestoneContentLoader = new MilestoneContentLoader(this, getLogger());
        milestoneCategories = milestoneContentLoader.load();
        storeContentLoader = new StoreContentLoader(this, getLogger());
        storeProducts = storeContentLoader.load();

        potionItem = new PotionItem(this);
        PlayerDataStore<AchievementProfile> achievementStore = PlayerStores.register(
                this, core.getListenerManager(), core.getDatabaseManager(),
                "achievements", AchievementProfile.class, AchievementProfile::new, "achievement data");
        PotionService potionService = new PotionService(packs.getPlayerStore(), achievementStore);
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

        // A server boost is a potion everybody is drinking at once, so it
        // feeds the very same registries - the profile argument is ignored
        // because the multiplier is shared, not per-player.
        serverBoostService = new ServerBoostService(this, core.getDatabaseManager(),
                new ServerBoostStore(core.getDatabaseManager()));
        packs.registerCoinMultiplierProvider(BOOST_PROVIDER_KEY, profile -> serverBoostService.multiplierFor(PotionStat.COINS));
        packs.registerDamageMultiplierProvider(BOOST_PROVIDER_KEY, profile -> serverBoostService.multiplierFor(PotionStat.DAMAGE));
        packs.getPackOpenService().registerCooldownMultiplierProvider(BOOST_PROVIDER_KEY, profile -> serverBoostService.multiplierFor(PotionStat.ROLL_SPEED));
        // Same additive-vs-multiplicative conversion the potion line above
        // documents - LuckService sums bonuses rather than multiplying them.
        packs.getLuckService().registerExtraLuckProvider(BOOST_PROVIDER_KEY, profile -> serverBoostService.multiplierFor(PotionStat.LUCK) - 1.0);
        serverBoostService.start();

        // Permanent, server-wide, coins only - see DonationGoalService for
        // why it is deliberately not damage or luck.
        donationGoalService = new DonationGoalService(this, core.getDatabaseManager(),
                new DonationGoalStore(core.getDatabaseManager()));
        packs.registerCoinMultiplierProvider(DONATION_PROVIDER_KEY, profile -> donationGoalService.coinMultiplier());
        donationGoalService.start();
        pendingPurchaseStore = new PendingPurchaseStore(core.getDatabaseManager());
        core.getListenerManager().register(new PendingPurchaseListener(this, core.getDatabaseManager(),
                pendingPurchaseStore, packStore));

        AchievementService achievementService = new AchievementService(() -> achievements, packs.getPlayerStore(), achievementStore);
        MilestoneService milestoneService = new MilestoneService(() -> milestoneCategories, packs.getPlayerStore(), achievementStore, potionItem);
        StoreService storeService = new StoreService(() -> storeProducts, packs.getPlayerStore());

        AchievementsGui achievementsGui = new AchievementsGui(() -> achievements, packs.getPlayerStore(), achievementService, core.getGuiManager());
        MilestoneCategoryGui categoryGui = new MilestoneCategoryGui(() -> milestoneCategories, packs.getPlayerStore(), milestoneService, core.getGuiManager());
        MilestonesGui milestonesGui = new MilestonesGui(() -> milestoneCategories, packs.getPlayerStore(), milestoneService, core.getGuiManager(), categoryGui);
        categoryGui.setHubGui(milestonesGui);
        StoreGui storeGui = new StoreGui(() -> storeProducts, packs.getPlayerStore(), storeService);
        PotionsGui potionsGui = new PotionsGui(packs.getPlayerStore(), potionService, core.getGuiManager());

        core.getListenerManager().register(new ProgressEventListener(achievementService, milestoneService));

        CommandManager.register(this, AchievementsCommand.build(achievementsGui), "View your achievements");
        CommandManager.register(this, MilestonesCommand.build(milestonesGui), "View your milestone progress");
        // "/buy" opens the SHARED Store hub (yield-packs) - this is the
        // Buycraft/Tebex-style storefront, so its tabs mirror what an actual
        // webstore sells (Ranks, Gamepasses), NOT unrelated in-game-currency
        // grind systems like Rankup or the Pack Shop. Bundles and Exclusive
        // Crates are placeholders until the Keys economy (crates opened with
        // farmed Keys, Key bundles sold here for Credits) is designed.
        packs.registerStoreCategory(new StoreCategory("ranks", 10,
                selected -> categoryIcon(Material.GOLD_BLOCK, "RANKS", selected, " &7Permanent donor ranks -", " &7VIP, Celestial, and their perks."),
                (player, gui, hub) -> storeGui.renderInto(player, gui, hub, StoreProductCategory.RANK)));
        packs.registerStoreCategory(new StoreCategory("gamepasses", 20,
                selected -> categoryIcon(Material.NETHER_STAR, "GAMEPASSES", selected, " &7Permanent unlocks and", " &7consumable potions."),
                (player, gui, hub) -> storeGui.renderInto(player, gui, hub, StoreProductCategory.GAMEPASS)));
        packs.registerStoreCategory(new StoreCategory("bundles", 30,
                selected -> categoryIcon(Material.CHEST, "BUNDLES", selected, " &7Coming soon."),
                (player, gui, hub) -> renderComingSoon(gui, "Bundles", "Coming soon.")));
        packs.registerStoreCategory(new StoreCategory("exclusive_crates", 40,
                selected -> categoryIcon(Material.ENDER_CHEST, "EXCLUSIVE CRATES", selected, " &7Coming soon."),
                (player, gui, hub) -> renderComingSoon(gui, "Exclusive Crates", "Coming soon.")));
        CommandManager.register(this, BuyCommand.build(packs.getStoreHubGui()), "Open the Store - Ranks, Gamepasses, Bundles and more");
        CommandManager.register(this, PotionsCommand.build(potionsGui), "View your active potions");

        core.getAdminCommandRegistry().register(buildAchievementsAdminCommand(achievementService));
        core.getAdminCommandRegistry().register(buildMilestonesAdminCommand(milestoneService));
        core.getAdminCommandRegistry().register(buildPotionsAdminCommand());
        core.getAdminCommandRegistry().register(buildBoostAdminCommand());
        core.getAdminCommandRegistry().register(buildStoreAdminCommand());
    }

    private static ItemStack categoryIcon(Material material, String label, boolean selected, String... descriptionLines) {
        ItemBuilder builder = ItemBuilder.of(material).name(MenuLore.buttonName("<#FFD700>", label));
        MenuLore.button("store", List.of(descriptionLines), "<#FFD700>", selected ? "Selected" : "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** A placeholder tab for a category that isn't built yet - a single info panel and a close button, no purchase flow. */
    private static void renderComingSoon(Gui gui, String label, String description) {
        for (int slot : StoreHubGui.CONTENT_SLOTS) {
            gui.set(slot, GuiIcons.filler(), null);
        }
        ItemBuilder builder = ItemBuilder.of(Material.BARRIER).name("<gray><bold>" + label.toUpperCase(Locale.ROOT) + "</bold></gray>");
        MenuLore.info("store", List.of(), "<gray>", List.of(description)).forEach(builder::lore);
        gui.set(31, builder.hideAttributes().build(), null);
        gui.set(49, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
    }

    /**
     * {@code /admin store purchase <player> <credits> [package name]} - the
     * command Tebex runs on a completed payment, e.g.
     * {@code admin store purchase %player_name% 499 VIP Rank}.
     * <p>
     * It does three things in one call so a Tebex package only ever needs
     * one line: grants the credits, announces the purchase, and counts it
     * toward the community donation goal.
     * <p>
     * The target is a raw NAME, not a player selector, because most
     * webstore purchases happen on the site while the buyer is offline -
     * that is the common case, not an edge case. An offline buyer's credits
     * are queued (see {@code PendingPurchaseStore}) and handed over the
     * moment they next join, so a purchase can never be silently dropped
     * and the Tebex package does not need to be marked "online only". The
     * donation goal is credited immediately either way: the money was paid
     * whether or not anyone is logged in to see it.
     * <p>
     * {@code /admin store setgoal <creditsTowardGoal> <goalsCompleted>} is
     * the correction path for a refund or a chargeback; it is silent on
     * purpose.
     */
    private LiteralCommandNode<CommandSourceStack> buildStoreAdminCommand() {
        return Commands.literal("store")
                .then(Commands.literal("purchase")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("credits", IntegerArgumentType.integer(1))
                                        .executes(ctx -> grantPurchase(ctx, null))
                                        .then(Commands.argument("package", StringArgumentType.greedyString())
                                                .executes(ctx -> grantPurchase(ctx, StringArgumentType.getString(ctx, "package")))))))
                .then(Commands.literal("setgoal")
                        .then(Commands.argument("creditsTowardGoal", IntegerArgumentType.integer(0))
                                .then(Commands.argument("goalsCompleted", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            donationGoalService.setProgress(
                                                    IntegerArgumentType.getInteger(ctx, "creditsTowardGoal"),
                                                    IntegerArgumentType.getInteger(ctx, "goalsCompleted"));
                                            ctx.getSource().getSender().sendMessage(Text.parse("<green>Donation progress updated.</green>"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("goal")
                        .executes(ctx -> {
                            var progress = donationGoalService.progress();
                            ctx.getSource().getSender().sendMessage(Text.parse(
                                    "<gray>Toward this goal:</gray> <white>$" + String.format(Locale.ROOT, "%.2f", progress.dollarsTowardGoal())
                                            + "</white> <gray>/ $100  |  goals completed:</gray> <white>" + progress.goalsCompleted()
                                            + "</white> <gray>(" + progress.permanentMultiplier() + "x coins)  |  lifetime:</gray> <white>"
                                            + progress.lifetimeCredits() + " credits</white>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private int grantPurchase(CommandContext<CommandSourceStack> ctx, String packageName) {
        String name = StringArgumentType.getString(ctx, "player");
        long credits = IntegerArgumentType.getInteger(ctx, "credits");
        Player online = Bukkit.getPlayerExact(name);

        if (online != null) {
            PackPlayerProfile profile = packStore.getOrCreate(online.getUniqueId());
            profile.setCredits(profile.getCredits().add(BigInteger.valueOf(credits)));
            packStore.save(online.getUniqueId());
            online.sendMessage(Text.parse("<green>Thank you! <white><credits></white> Credits have been added to your account.</green>",
                    Placeholder.unparsed("credits", Formatting.format(credits))));
        } else {
            // Off the main thread - a Mongo insert must never block a tick,
            // and nothing downstream depends on it having finished.
            PendingPurchaseStore queue = pendingPurchaseStore;
            JavaPlugin.getPlugin(YieldCore.class).getDatabaseManager().supplyAsync(() -> {
                queue.queue(name, credits, packageName);
                return null;
            });
        }

        // Announced and counted regardless of whether the buyer is around to
        // see it - the purchase happened either way.
        donationGoalService.announcePurchase(online != null ? online.getName() : name, credits, packageName);
        donationGoalService.recordPurchase(credits);

        ctx.getSource().getSender().sendMessage(Text.parse("<green>Granted <white>" + credits + "</white> credits to "
                + name + (online != null ? "" : " (queued - they're offline)") + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * {@code /admin boost start <stat> <multiplier> <seconds>} and
     * {@code /admin boost stop <stat> <multiplier>}, plus a listing. Admin-driven
     * rather than config-driven on purpose: a server boost is an event
     * somebody decides to run, not a schedule - and anything that should be
     * automatic can call {@code ServerBoostService#startBoost} directly.
     */
    private LiteralCommandNode<CommandSourceStack> buildBoostAdminCommand() {
        return Commands.literal("boost")
                .then(Commands.literal("start")
                        .then(Commands.argument("stat", StringArgumentType.word())
                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    PotionStat stat = parseStat(ctx.getSource(), StringArgumentType.getString(ctx, "stat"));
                                                    if (stat == null) {
                                                        return Command.SINGLE_SUCCESS;
                                                    }
                                                    serverBoostService.startBoost(stat,
                                                            DoubleArgumentType.getDouble(ctx, "multiplier"),
                                                            IntegerArgumentType.getInteger(ctx, "seconds"),
                                                            ctx.getSource().getSender().getName());
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("stat", StringArgumentType.word())
                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> {
                                            PotionStat stat = parseStat(ctx.getSource(), StringArgumentType.getString(ctx, "stat"));
                                            if (stat == null) {
                                                return Command.SINGLE_SUCCESS;
                                            }
                                            boolean stopped = serverBoostService.stopBoost(stat, DoubleArgumentType.getDouble(ctx, "multiplier"));
                                            ctx.getSource().getSender().sendMessage(stopped
                                                    ? Text.parse("<green>Boost ended.</green>")
                                                    : Text.parse("<red>No boost like that is running.</red>"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            List<ServerBoost> boosts = serverBoostService.activeBoosts();
                            if (boosts.isEmpty()) {
                                ctx.getSource().getSender().sendMessage(Text.parse("<gray>No server boosts are running.</gray>"));
                                return Command.SINGLE_SUCCESS;
                            }
                            for (ServerBoost boost : boosts) {
                                ctx.getSource().getSender().sendMessage(Text.parse("<white>" + boost.multiplierLabel() + " "
                                        + boost.stat().name() + "</white> <gray>- " + boost.remainingSeconds() + "s left</gray>"));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    /** Resolves a stat name for the boost command, reporting the valid set rather than failing silently. */
    private PotionStat parseStat(CommandSourceStack source, String raw) {
        try {
            return PotionStat.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.getSender().sendMessage(Text.parse("<red>Unknown stat '" + raw + "' - expected one of "
                    + Arrays.toString(PotionStat.values()) + ".</red>"));
            return null;
        }
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
