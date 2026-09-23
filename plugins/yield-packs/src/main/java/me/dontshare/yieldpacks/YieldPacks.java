package me.dontshare.yieldpacks;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.chat.RebirthChatBadge;
import me.dontshare.yieldpacks.command.AutoTargetCommand;
import me.dontshare.yieldpacks.command.BagCommand;
import me.dontshare.yieldpacks.command.HugeIndexCommand;
import me.dontshare.yieldpacks.command.IndexCommand;
import me.dontshare.yieldpacks.command.PackStorageCommand;
import me.dontshare.yieldpacks.command.PacksAdminCommand;
import me.dontshare.yieldpacks.command.PetVisibilityCommand;
import me.dontshare.yieldpacks.command.PetsAdminCommand;
import me.dontshare.yieldpacks.command.RankupCommand;
import me.dontshare.yieldpacks.command.RollAnimationCommand;
import me.dontshare.yieldpacks.command.SendModeCommand;
import me.dontshare.yieldpacks.command.SettingsCommand;
import me.dontshare.yieldpacks.command.StatsAdminCommand;
import me.dontshare.yieldpacks.command.StoreCommand;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackRegistry;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.display.PetDisplayConfig;
import me.dontshare.yieldpacks.display.PetDisplayConfigLoader;
import me.dontshare.yieldpacks.display.PetDisplayListener;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.display.PetLevelUpEffectListener;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.enchant.EnchantCommand;
import me.dontshare.yieldpacks.enchant.EnchantGui;
import me.dontshare.yieldpacks.enchant.EnchantItem;
import me.dontshare.yieldpacks.enchant.EnchantMarketCommand;
import me.dontshare.yieldpacks.enchant.EnchantMarketGui;
import me.dontshare.yieldpacks.enchant.EnchantMarketService;
import me.dontshare.yieldpacks.enchant.EnchantService;
import me.dontshare.yieldpacks.enchant.EnchantType;
import me.dontshare.yieldpacks.event.PetCandyFedEvent;
import me.dontshare.yieldpacks.fusion.AutoFuseService;
import me.dontshare.yieldpacks.fusion.FusionService;
import me.dontshare.yieldpacks.gui.BagGui;
import me.dontshare.yieldpacks.gui.DeleteByRarityGui;
import me.dontshare.yieldpacks.gui.FusionGui;
import me.dontshare.yieldpacks.gui.HugeIndexGui;
import me.dontshare.yieldpacks.gui.IndexGui;
import me.dontshare.yieldpacks.gui.PackOddsLore;
import me.dontshare.yieldpacks.gui.PackShopGui;
import me.dontshare.yieldpacks.gui.EggCatalogGui;
import me.dontshare.yieldpacks.gui.HatchMenuGui;
import me.dontshare.yieldpacks.gui.RankupGui;
import me.dontshare.yieldpacks.gui.SettingsGui;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.leveling.Candy;
import me.dontshare.yieldpacks.leveling.CandyContentLoader;
import me.dontshare.yieldpacks.leveling.CandyItem;
import me.dontshare.yieldpacks.leveling.PetLevelingConfig;
import me.dontshare.yieldpacks.leveling.PetLevelingContentLoader;
import me.dontshare.yieldpacks.leveling.PetLevelingService;
import me.dontshare.yieldpacks.mastery.MasteryCommand;
import me.dontshare.yieldpacks.mastery.MasteryConfig;
import me.dontshare.yieldpacks.mastery.MasteryContentLoader;
import me.dontshare.yieldpacks.mastery.MasteryGui;
import me.dontshare.yieldpacks.mastery.MasteryService;
import me.dontshare.yieldpacks.mastery.MasteryStat;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.pet.PetInstanceMigration;
import me.dontshare.yieldpacks.pet.PetItemFeedListener;
import me.dontshare.yieldpacks.pet.PetItemHandler;
import me.dontshare.yieldpacks.pet.PetRedeemListener;
import me.dontshare.yieldpacks.pet.PetWithdrawItem;
import me.dontshare.yieldpacks.petenchant.AutoEnchantGui;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader;
import me.dontshare.yieldpacks.petenchant.PetEnchantSelectGui;
import me.dontshare.yieldpacks.petenchant.PetEnchantService;
import me.dontshare.yieldpacks.petenchant.PetEnchantTableDisplay;
import me.dontshare.yieldpacks.petenchant.PetEnchantTableGui;
import me.dontshare.yieldpacks.pity.PityService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.StoredEggRefundListener;
import me.dontshare.yieldpacks.player.PackPlayerProfileManager;
import me.dontshare.yieldpacks.rank.RankService;
import me.dontshare.yieldpacks.roll.ExistsCounterStore;
import me.dontshare.yieldpacks.roll.PackOpenService;
import me.dontshare.yieldpacks.roll.PackRevealAnimationService;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpacks.selector.BagSelectorItem;
import me.dontshare.yieldpacks.selector.BagSelectorListener;
import me.dontshare.yieldpacks.selector.PackActionBarService;
import me.dontshare.yieldpacks.selector.PackSelectorItem;
import me.dontshare.yieldpacks.selector.PackSelectorListener;
import me.dontshare.yieldpacks.selector.PackSelectorService;
import me.dontshare.yieldpacks.shard.ShardConsumeListener;
import me.dontshare.yieldpacks.shard.ShardItem;
import me.dontshare.yieldpacks.shard.ShardService;
import me.dontshare.yieldpacks.shop.ShopStockService;
import me.dontshare.yieldpacks.starter.StarterPetGui;
import me.dontshare.yieldpacks.starter.StarterPetJoinListener;
import me.dontshare.yieldpacks.starter.StarterPetService;
import me.dontshare.yieldpacks.store.StoreCategory;
import me.dontshare.yieldpacks.store.StoreHubGui;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class YieldPacks extends JavaPlugin {

    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60 * 2; // 2 minutes
    private static final int EQUIP_CAP = 6;

    private PackContentLoader contentLoader;
    private volatile PackContentLoader.ContentSnapshot content;

    private PlayerDataStore<PackPlayerProfile> playerStore;
    private PackPlayerProfileManager profileManager;
    private EquipmentService equipmentService;
    private LuckService luckService;
    private PetDisplayService petDisplayService;
    private PetDisplayConfigLoader petDisplayConfigLoader;
    private PackOpenService openService;
    private PetLevelingContentLoader petLevelingContentLoader;
    private volatile PetLevelingConfig petLevelingConfig;
    private PetLevelingService petLevelingService;
    private PackRollService rollService;
    private me.dontshare.yieldpacks.storage.BagStorageService bagStorageService;
    private ItemIconFactory iconFactory;
    private EggCatalogGui eggCatalogGui;
    private HatchMenuGui hatchMenuGui;
    private CandyContentLoader candyContentLoader;
    private volatile Map<String, Candy> candyConfig;
    private CandyItem candyItem;
    private FusionGui fusionGui;
    private RankService rankService;
    private RankupGui rankupGui;
    private ShardService shardService;
    private ShardItem shardItem;
    private EnchantService enchantService;
    private EnchantGui enchantGui;
    private EnchantMarketService enchantMarketService;
    private MasteryContentLoader masteryContentLoader;
    private volatile MasteryConfig masteryConfig;
    private MasteryService masteryService;
    private PetEnchantContentLoader petEnchantContentLoader;
    private volatile PetEnchantContentLoader.PetEnchantContent petEnchantContent;
    private PetEnchantService petEnchantService;
    private PetEnchantTableDisplay petEnchantTableDisplay;

    /**
     * Composable stat-multiplier registries - any number of satellite
     * plugins can each register their own keyed provider (e.g. yield-rebirth
     * registers "rebirth", yield-skilltree registers "skilltree") without a
     * compile-time dependency on yield-packs in the other direction, and
     * without one plugin's registration silently clobbering another's the
     * way a single overwritable slot would. {@link #coinMultiplier} and
     * {@link #damageMultiplier} multiply every currently-registered
     * provider's result together. Coin multiplier applies to every coin
     * payout, including ore-cube kills (see yield-zones' {@code
     * OreCubeService}); damage multiplier applies to pet attack damage
     * (see yield-zones' {@code PetCombatController}). Attack-speed
     * multiplier likewise shortens the tick interval between a pet's hits -
     * a home for a future potion system (damage/attack-speed/coin/diamond
     * potions) to plug into without hardcoding anything pet-combat-side.
     */
    private final Map<String, Function<PackPlayerProfile, Double>> coinMultiplierProviders = new ConcurrentHashMap<>();
    private final Map<String, Function<PackPlayerProfile, Double>> damageMultiplierProviders = new ConcurrentHashMap<>();
    private final Map<String, Function<PackPlayerProfile, Double>> attackSpeedMultiplierProviders = new ConcurrentHashMap<>();
    /** Speeds up Auto Mode's own target-switch cooldown - see yield-zones' {@code PetCombatController}, distinct from attack-speed (the interval between hits on the SAME target). */
    private final Map<String, Function<PackPlayerProfile, Double>> autoSwitchSpeedMultiplierProviders = new ConcurrentHashMap<>();
    /** Applies to diamond payouts (see yield-zones' {@code OreCubeService}) - "rank" self-registers into this one below, but it stays a composable registry (not a direct call) so a future potion/upgrade can stack another contribution on top. */
    private final Map<String, Function<PackPlayerProfile, Double>> diamondMultiplierProviders = new ConcurrentHashMap<>();
    /** "Apply this held item to a pet" gestures - see {@link PetItemHandler}. Candy registers itself as one of these below; yield-mining's forged held items register their own externally. Tried in order; the first to return true wins. */
    private final List<PetItemHandler> petItemHandlers = new CopyOnWriteArrayList<>();
    /** Every tab {@link StoreHubGui} shows in the Buycraft-style Store - see {@link #registerStoreCategory}. yield-achievements (which owns the Credits Store) registers all of them from its own onEnable; yield-packs contributes none of its own. */
    private final Map<String, StoreCategory> storeCategories = new ConcurrentHashMap<>();
    private StoreHubGui storeHubGui;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        contentLoader = new PackContentLoader(this, getLogger());
        content = contentLoader.load();

        playerStore = new PlayerDataStore<>(core.getDatabaseManager(), "playerData", "packs",
                PackPlayerProfile.class, PackPlayerProfile::new, getLogger());
        playerStore.setRawMigration(PetInstanceMigration::migrate);
        profileManager = new PackPlayerProfileManager(playerStore, getLogger());
        core.getListenerManager().register(profileManager);
        playerStore.startAutoSave(this, AUTOSAVE_INTERVAL_TICKS);

        equipmentService = new EquipmentService(() -> content.items(), EQUIP_CAP);
        luckService = new LuckService(() -> content.packs());
        // A permanent, admin-granted luck bonus (see PacksAdminCommand's
        // "stats" subcommand) - its own keyed slot, same shape as every
        // other LuckService contributor (skill tree, teams, potions).
        luckService.registerExtraLuckProvider("admin", PackPlayerProfile::getAdminLuckBonus);

        petLevelingContentLoader = new PetLevelingContentLoader(this, getLogger());
        petLevelingConfig = petLevelingContentLoader.load();
        candyContentLoader = new CandyContentLoader(this, getLogger());
        candyConfig = candyContentLoader.load();
        candyItem = new CandyItem(this);
        petLevelingService = new PetLevelingService(() -> petLevelingConfig, playerStore, () -> candyConfig, candyItem);
        equipmentService.setLevelMultiplierProvider(petLevelingService::levelMultiplier);
        equipmentService.setShinyDamageMultiplier(() -> content.variants().shinyDamageMultiplier());
        registerPetItemHandler((player, pet, item) -> {
            var candyOpt = petLevelingService.candyFor(item);
            if (candyOpt.isEmpty()) {
                return false;
            }
            Candy c = candyOpt.get();
            petLevelingService.applyCandyDirect(pet, c);
            item.setAmount(item.getAmount() - 1);
            Bukkit.getPluginManager().callEvent(new PetCandyFedEvent(player, pet.getInstanceId(), c));
            player.sendMessage(Text.parse("<green>Fed <name> - level cap raised by +<bonus>!</green>",
                    Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(c.displayName())),
                    Placeholder.unparsed("bonus", String.valueOf(c.levelCapBonus()))));
            return true;
        });

        ExistsCounterStore existsCounterStore = new ExistsCounterStore(core.getDatabaseManager(), getLogger());
        existsCounterStore.loadAll();

        iconFactory = new ItemIconFactory();
        petDisplayConfigLoader = new PetDisplayConfigLoader(this);
        PetDisplayConfig displayConfig = petDisplayConfigLoader.load();
        petDisplayService = new PetDisplayService(this, playerStore, () -> content.items(), () -> content.rarities(),
                iconFactory, displayConfig);
        petDisplayService.start();
        core.getListenerManager().register(new PetDisplayListener(petDisplayService));
        core.getListenerManager().register(new PetLevelUpEffectListener(petDisplayService));

        StarterPetService starterPetService = new StarterPetService(playerStore, equipmentService, petDisplayService);
        StarterPetGui starterPetGui = new StarterPetGui(core.getGuiManager(), iconFactory, () -> content.items(), starterPetService);
        core.getListenerManager().register(new StarterPetJoinListener(starterPetService, starterPetGui, this));
        core.getListenerManager().register(new StoredEggRefundListener(this, playerStore, () -> content));

        ShopStockService stockService = new ShopStockService(() -> content, playerStore, luckService);
        PityService pityService = new PityService(() -> content);

        rollService = new PackRollService(() -> content, playerStore, equipmentService, luckService,
                existsCounterStore, petDisplayService, stockService, pityService);
        bagStorageService = new me.dontshare.yieldpacks.storage.BagStorageService(playerStore);
        rollService.setStorage(bagStorageService);

        core.getPlaceholderRegistry().register("coins", player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            return profile != null ? Formatting.format(profile.getCoins()) : "0";
        });
        core.getPlaceholderRegistry().register("diamonds", player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            return profile != null ? Formatting.format(profile.getDiamonds()) : "0";
        });
        core.getChatFormatter().registerNameTagProvider(new RebirthChatBadge(playerStore));

        core.getScoreboardDisplay().addLineProvider(player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            if (profile == null) {
                return List.of();
            }
            double totalDamage = profile.getEquippedPetIds().stream()
                    .flatMap(id -> profile.findPet(id).stream())
                    .mapToDouble(pet -> equipmentService.effectiveDamage(profile, pet))
                    .sum() * damageMultiplier(profile);
            return List.of(
                    " ",
                    "<#8CD5EC> " + Formatting.fancyFont("player"),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("cubes broken: ") + "<#EC8C9F>" + Formatting.format(profile.getLifetimeCubeKills()),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("rebirths: ") + RebirthChatBadge.prefixFor(profile.getRebirths()),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("multi: ") + "<#95EC8C>" + Formatting.format(coinMultiplier(profile)) + "x",
                    " ",
                    "<#8CD5EC> " + Formatting.fancyFont("wallet"),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("coins: ") + "<#F9FF91>" + Formatting.format(profile.getCoins()),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("diamonds: ") + "<#ADFFF5>" + Formatting.format(profile.getDiamonds()),
                    " <#8CD5EC>&l| " + "&f" + Formatting.fancyFont("credits: ") + "&f" + Formatting.format(profile.getCredits()),
                    " ",
                    "&f" + Formatting.fancyFont("yield.minehut.gg")
            );
        });

        EnchantItem enchantItem = new EnchantItem(this);
        enchantService = new EnchantService(playerStore, () -> content.rarities(), enchantItem);
        registerCoinMultiplierProvider("enchants", enchantService.multiplierFor(EnchantType.COINS));
        registerDiamondMultiplierProvider("enchants", enchantService.multiplierFor(EnchantType.DIAMONDS));
        registerDamageMultiplierProvider("enchants", enchantService.multiplierFor(EnchantType.DAMAGE));
        registerAttackSpeedMultiplierProvider("enchants", enchantService.multiplierFor(EnchantType.ATTACK_SPEED));
        luckService.registerExtraLuckProvider("enchants", enchantService.additiveFor(EnchantType.LUCK));

        masteryContentLoader = new MasteryContentLoader(this, getLogger());
        masteryConfig = masteryContentLoader.load();
        masteryService = new MasteryService(() -> masteryConfig, playerStore);
        luckService.registerExtraLuckProvider("mastery_packs",
                profile -> masteryService.sumStat(profile, MasteryType.PACKS, MasteryStat.LUCK));
        registerCoinMultiplierProvider("mastery_mining",
                profile -> 1.0 + masteryService.sumStat(profile, MasteryType.MINING, MasteryStat.COIN_MULTIPLIER));
        registerDamageMultiplierProvider("mastery_combat",
                profile -> 1.0 + masteryService.sumStat(profile, MasteryType.COMBAT, MasteryStat.DAMAGE_MULTIPLIER));
        registerAttackSpeedMultiplierProvider("mastery_combat",
                profile -> 1.0 + masteryService.sumStat(profile, MasteryType.COMBAT, MasteryStat.ATTACK_SPEED_MULTIPLIER));
        registerDiamondMultiplierProvider("mastery_refinery",
                profile -> 1.0 + masteryService.sumStat(profile, MasteryType.REFINERY, MasteryStat.DIAMOND_MULTIPLIER));
        luckService.registerExtraLuckProvider("mastery_refinery",
                profile -> masteryService.sumStat(profile, MasteryType.REFINERY, MasteryStat.LUCK));
        equipmentService.registerBonusEquipSlotsProvider("mastery_packs",
                profile -> (int) Math.round(masteryService.sumStat(profile, MasteryType.PACKS, MasteryStat.EXTRA_PET_SLOTS)));
        enchantService.registerBonusSlotProvider("mastery_refinery",
                profile -> (int) Math.round(masteryService.sumStat(profile, MasteryType.REFINERY, MasteryStat.ENCHANT_BONUS_SLOTS)));

        petEnchantContentLoader = new PetEnchantContentLoader(this);
        petEnchantContent = petEnchantContentLoader.load();
        petEnchantService = new PetEnchantService(playerStore, equipmentService, () -> petEnchantContent);
        registerCoinMultiplierProvider("pet_enchants", petEnchantService::coinMultiplier);
        registerDiamondMultiplierProvider("pet_enchants", petEnchantService::diamondMultiplier);
        registerAttackSpeedMultiplierProvider("pet_enchants", petEnchantService::attackSpeedMultiplier);
        luckService.registerExtraLuckProvider("pet_enchants", petEnchantService::luckBonus);

        PackRevealAnimationService reelAnimationService = new PackRevealAnimationService(this, () -> content,
                rollService, pityService, () -> content.rarities());
        openService = new PackOpenService(this, () -> content, playerStore, rollService, reelAnimationService,
                masteryService);
        openService.registerCooldownMultiplierProvider("mastery_packs",
                profile -> 1.0 - masteryService.sumStat(profile, MasteryType.PACKS, MasteryStat.OPEN_SPEED_MULTIPLIER));
        openService.start();

        enchantGui = new EnchantGui(playerStore, enchantService, enchantItem, () -> content.rarities(), core.getGuiManager());
        CommandManager.register(this, EnchantCommand.build(enchantGui), "Drag-and-drop enchant slots", List.of());
        enchantMarketService = new EnchantMarketService(this, playerStore, () -> content.rarities(), enchantService);
        enchantMarketService.start();
        EnchantMarketGui enchantMarketGui = new EnchantMarketGui(enchantMarketService, playerStore, core.getGuiManager());
        enchantMarketGui.setEnchantGui(enchantGui);
        enchantGui.setMarketGui(enchantMarketGui);
        CommandManager.register(this, EnchantMarketCommand.build(enchantMarketGui),
                "This hour's Enchant Books - restocks for everyone at the top of the hour", List.of("emarket"));
        MasteryGui masteryGui = new MasteryGui(playerStore, masteryService, core.getGuiManager());
        CommandManager.register(this, MasteryCommand.build(masteryGui), "View your mastery progress", List.of());

        PackOddsLore oddsLore = new PackOddsLore(rollService);
        PackShopGui packShopGui = new PackShopGui(stockService, core.getGuiManager(), rollService, oddsLore, openService);
        eggCatalogGui = new EggCatalogGui(() -> content, core.getGuiManager(), oddsLore);
        hatchMenuGui = new HatchMenuGui(() -> content, core.getGuiManager(), oddsLore, openService, rollService, iconFactory, playerStore);
        FusionService fusionService = new FusionService(() -> content.items());
        fusionGui = new FusionGui(playerStore, () -> content.items(), () -> content.rarities(),
                fusionService, core.getGuiManager(), iconFactory);
        new AutoFuseService(this, playerStore, fusionService).start();

        rankService = new RankService(playerStore);
        registerDiamondMultiplierProvider("rank", rankService::diamondMultiplier);
        rankupGui = new RankupGui(playerStore, core.getGuiManager(), rankService);
        // "ranks" is an alias, not a separate command - it replaces the old
        // bare /ranks (donor-rank info readout, removed from yield-ranks),
        // per the decision that Rankup - the earnable prestige ladder - is
        // what "/ranks" should mean now, not premium donor ranks (those live
        // in the Credits Store, reachable via the Store category below).
        // "rankquests" is also just an alias to this SAME screen - the Rank
        // Quest board renders inline here (row 0, via RankQuestSource,
        // registered by yield-quests once it enables) rather than living in
        // its own separate GUI.
        CommandManager.register(this, RankupCommand.build(rankupGui), "Open the Rankup menu", List.of("ranks", "rankquests"));

        shardService = new ShardService(playerStore);
        shardItem = new ShardItem(this);
        registerDamageMultiplierProvider("shards", shardService::damageMultiplier);
        registerCoinMultiplierProvider("shards", shardService::coinMultiplier);
        registerDiamondMultiplierProvider("shards", shardService::diamondMultiplier);
        registerAttackSpeedMultiplierProvider("shards", shardService::attackSpeedMultiplier);
        luckService.registerExtraLuckProvider("shards", shardService::luckBonus);
        core.getListenerManager().register(new ShardConsumeListener(shardItem, shardService));

        DeleteByRarityGui deleteByRarityGui = new DeleteByRarityGui(playerStore, () -> content.items(), () -> content.rarities(), core.getGuiManager());
        PetWithdrawItem withdrawItem = new PetWithdrawItem(this, iconFactory, petLevelingService, () -> petEnchantContent);
        core.getListenerManager().register(new PetRedeemListener(withdrawItem, playerStore, petDisplayService, bagStorageService));
        core.getListenerManager().register(new PetItemFeedListener(withdrawItem, playerStore, () -> content.items(),
                () -> content.rarities(), equipmentService, this::applyPetItemHandlers));
        BagGui bagGui = new BagGui(playerStore, () -> content.items(), () -> content.rarities(), equipmentService,
                core.getGuiManager(), iconFactory, petDisplayService, deleteByRarityGui, petLevelingService,
                withdrawItem, existsCounterStore, this::applyPetItemHandlers, () -> petEnchantContent);
        bagGui.setStorage(bagStorageService);
        IndexGui indexGui = new IndexGui(() -> content, playerStore, luckService, core.getGuiManager(), iconFactory);
        HugeIndexGui hugeIndexGui = new HugeIndexGui(() -> content, playerStore, iconFactory, core.getGuiManager());
        indexGui.setHugeIndexGui(hugeIndexGui);

        PetEnchantSelectGui petEnchantSelectGui = new PetEnchantSelectGui(playerStore, () -> content.items(),
                () -> content.rarities(), equipmentService, iconFactory, petEnchantService, () -> petEnchantContent, core.getGuiManager());
        PetEnchantTableGui petEnchantTableGui = new PetEnchantTableGui(playerStore, () -> content.items(), () -> content.rarities(),
                equipmentService, iconFactory, petEnchantService, () -> petEnchantContent, core.getGuiManager(), this, masteryService);
        AutoEnchantGui autoEnchantGui = new AutoEnchantGui(petEnchantService, () -> petEnchantContent, core.getGuiManager());
        petEnchantSelectGui.setTableGui(petEnchantTableGui);
        petEnchantTableGui.setSelectGui(petEnchantSelectGui);
        petEnchantTableGui.setAutoEnchantGui(autoEnchantGui);
        autoEnchantGui.setTableGui(petEnchantTableGui);
        petEnchantTableDisplay = new PetEnchantTableDisplay(this, petEnchantTableGui);
        petEnchantTableDisplay.start();
        petEnchantTableDisplay.reload(petEnchantContent.tables());

        PackSelectorItem selectorItem = new PackSelectorItem(this);
        // Left-click on the compass opens this SAME PackStorageGui (already
        // built above, just before this block) - merged from what used to
        // be a separate, simpler pack-picker GUI, so /packs and the compass
        // are one feature, not two overlapping ones.
        PackSelectorService selectorService = new PackSelectorService(selectorItem, playerStore, () -> content, eggCatalogGui);
        core.getListenerManager().register(new PackSelectorListener(selectorService, selectorItem));
        PackActionBarService actionBarService = new PackActionBarService(this, playerStore, () -> content, pityService, selectorService, openService);
        actionBarService.start();

        BagSelectorItem bagSelectorItem = new BagSelectorItem(this);
        core.getListenerManager().register(new BagSelectorListener(bagSelectorItem, bagGui));

        // Both of these live in a fixed hotbar slot and are handed back for
        // free if lost, so they're worth nothing - but the listeners above
        // only stop vanilla item movement. Anything that moves items itself
        // (trading, listing on the Auction House) has to be told separately,
        // or it happily swallows a compass the player can never get back
        // out of it.
        core.getBoundItemRegistry().register(selectorItem::isPackSelector);
        core.getBoundItemRegistry().register(bagSelectorItem::isBagSelector);

        // /merchant (PacksCommand, opens packShopGui) is deliberately NOT
        // registered - it's the old pre-Store-hub Pack Shop system. Kept
        // (packShopGui itself, PacksCommand.java) rather than deleted, in
        // case it's wanted back later - just not reachable by command for now.
        CommandManager.register(this, PackStorageCommand.build(eggCatalogGui), "Browse every egg and what hatches from it", List.of("packs"));

        // The Store hub is the Buycraft/Tebex-style real-money storefront -
        // its own tabs (Ranks/Gamepasses/Bundles/Exclusive Crates) are
        // registered by yield-achievements, which owns the Credits Store.
        // Rankup and the Pack Shop are unrelated in-game-currency grind
        // systems and were deliberately pulled back out of it - they stay
        // reachable via their own commands (/rankup, /ranks, /merchant).
        storeHubGui = new StoreHubGui(core.getGuiManager(), () -> List.copyOf(storeCategories.values()));
        CommandManager.register(this, StoreCommand.build(storeHubGui), "Open the Store - Ranks, Gamepasses, Bundles and more", List.of());
        CommandManager.register(this, BagCommand.build(bagGui), "Open your bag", List.of("pets"));
        CommandManager.register(this, IndexCommand.build(indexGui), "Open your collection index", List.of());
        CommandManager.register(this, HugeIndexCommand.build(hugeIndexGui), "Browse every Huge pet in the game", List.of());
        core.getAdminCommandRegistry().register(PacksAdminCommand.build(this));
        core.getAdminCommandRegistry().register(StatsAdminCommand.build(this));
        core.getAdminCommandRegistry().register(PetsAdminCommand.build(this));
        CommandManager.register(this, RollAnimationCommand.build(playerStore), "Toggle the pack-opening roll animation", List.of());
        CommandManager.register(this, PetVisibilityCommand.build(playerStore, petDisplayService),
                "Set what equipped-pet displays you personally see", List.of());
        CommandManager.register(this, AutoTargetCommand.build(playerStore),
                "Set how your pets auto-target ore cubes (while Auto Attack is on)", List.of());
        CommandManager.register(this, SendModeCommand.build(playerStore),
                "Set whether your pets fight on their own or only when sent", List.of());
        SettingsGui settingsGui = new SettingsGui(playerStore, core.getGuiManager());
        CommandManager.register(this, SettingsCommand.build(settingsGui),
                "Open your player settings (send-mode single/all, etc.)", List.of());
    }

    @Override
    public void onDisable() {
        // Before the store is flushed, not after: a screen like the Enchants
        // menu reconciles the profile from its slots as it closes, and
        // plugins are disabled well before players are kicked, so that close
        // would otherwise never happen on a restart or /reload.
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        if (core.getGuiManager() != null) {
            core.getGuiManager().closeAll();
        }
        if (petDisplayService != null) {
            petDisplayService.shutdown();
        }
        if (profileManager != null) {
            profileManager.getStore().saveAllSync();
        }
    }

    /** Re-reads packs.yml, pet-display.yml, and pet-leveling.yml, atomically swapping in the new registries/config - no restart needed. */
    public void reloadContent() {
        content = contentLoader.load();
        if (petDisplayService != null) {
            petDisplayService.setConfig(petDisplayConfigLoader.load());
        }
        petLevelingConfig = petLevelingContentLoader.load();
        candyConfig = candyContentLoader.load();
        masteryConfig = masteryContentLoader.load();
        petEnchantContent = petEnchantContentLoader.load();
        if (petEnchantTableDisplay != null) {
            petEnchantTableDisplay.reload(petEnchantContent.tables());
        }
        if (enchantMarketService != null) {
            enchantMarketService.reload();
        }
    }

    public PlayerDataStore<PackPlayerProfile> getPlayerStore() {
        return playerStore;
    }

    public EquipmentService getEquipmentService() {
        return equipmentService;
    }

    public LuckService getLuckService() {
        return luckService;
    }

    public PackOpenService getPackOpenService() {
        return openService;
    }

    public PetDisplayService getPetDisplayService() {
        return petDisplayService;
    }

    public ItemRegistry getItemRegistry() {
        return content.items();
    }

    public RarityRegistry getRarityRegistry() {
        return content.rarities();
    }

    /** Exposed for the same reason as {@link #getItemRegistry()} - lets e.g. yield-packstations resolve a pack id (its own pack-station config references) against real loaded pack data. */
    public PackRegistry getPackRegistry() {
        return content.packs();
    }

    public PetLevelingService getPetLevelingService() {
        return petLevelingService;
    }

    /** Exposed for the same reason as {@link #getPetLevelingService()} - lets e.g. yield-blocktree register its own exclusive-find-chance provider. */
    /** Resolves an egg's or pet's icon, HeadDatabase head included - see yield-packstations, which renders the egg standing on each station. */
    public ItemIconFactory getIconFactory() {
        return iconFactory;
    }

    /** The right-click menu a physical egg station opens - see yield-packstations. */
    public HatchMenuGui getHatchMenuGui() {
        return hatchMenuGui;
    }

    public me.dontshare.yieldpacks.storage.BagStorageService getBagStorageService() {
        return bagStorageService;
    }

    public PackRollService getPackRollService() {
        return rollService;
    }

    /** Live candy type data, keyed by id (e.g. "common_candy") - lets another plugin (e.g. a physical candy-vending machine) look up a real Candy record to sell. */
    public Map<String, Candy> getCandyConfig() {
        return candyConfig;
    }

    /** The SAME CandyItem instance PetLevelingService's own feed-gesture recognizes - an item built by a different plugin's own CandyItem would carry a differently-namespaced tag and silently fail that recognition, so anything creating a real, feedable candy ItemStack must go through this one. */
    public CandyItem getCandyItem() {
        return candyItem;
    }

    /** Exposed so e.g. a physical walk-in trigger (yield-zonemachines) can open the SAME fusion menu the Bag's own Fusion button does, optionally filtered to one target tier - see {@link FusionGui#open(Player, me.dontshare.yieldpacks.fusion.FusionTier)}. */
    public FusionGui getFusionGui() {
        return fusionGui;
    }

    public RankService getRankService() {
        return rankService;
    }

    public RankupGui getRankupGui() {
        return rankupGui;
    }

    /** Exposed so a physical walk-in trigger (yield-zonemachines) can open the same drag-and-drop Enchant Slots menu {@code /enchants} does. */
    public EnchantGui getEnchantGui() {
        return enchantGui;
    }

    /** For a caller (e.g. yield-blocktree's own progress listener) that needs to GIVE a Shard drop - the actual permanent-bonus math and consume gesture live entirely in ShardService/ShardConsumeListener. */
    public ShardService getShardService() {
        return shardService;
    }

    public ShardItem getShardItem() {
        return shardItem;
    }

    public EnchantMarketService getEnchantMarketService() {
        return enchantMarketService;
    }

    public EnchantService getEnchantService() {
        return enchantService;
    }

    public MasteryService getMasteryService() {
        return masteryService;
    }

    /** Exposed so e.g. yield-zones' OreCubeService can check the Glittering Unique's bonus-diamond-drop hook (see {@code PetEnchantService#hasBonusDiamondDropEnchant}) right alongside {@code MilestoneEffect.GUARANTEED_DIAMOND_DROP}. */
    public PetEnchantService getPetEnchantService() {
        return petEnchantService;
    }

    /** The current global coin multiplier - the product of every currently-registered provider (1.0 if none are registered). Apply to every coin payout. */
    public double coinMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : coinMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** A compact 10-square progress bar toward the next level - same visual convention PityService#renderBar already established, just legacy-color-coded instead of MiniMessage since scoreboard lines are plain strings. */
    private String levelBar(float progress) {
        int filled = Math.round(progress * 10);
        StringBuilder builder = new StringBuilder("&b");
        for (int i = 0; i < 10; i++) {
            builder.append(i < filled ? "■" : "&8□");
        }
        return builder.toString();
    }

    /** Registers (or replaces) this plugin's own keyed coin-multiplier contribution - e.g. {@code registerCoinMultiplierProvider("rebirth", rebirthService::coinMultiplier)}. */
    public void registerCoinMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        coinMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterCoinMultiplierProvider(String key) {
        coinMultiplierProviders.remove(key);
    }

    /** The current global damage multiplier - the product of every currently-registered provider (1.0 if none are registered). Apply to pet attack damage. */
    public double damageMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : damageMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Registers (or replaces) this plugin's own keyed damage-multiplier contribution. */
    public void registerDamageMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        damageMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterDamageMultiplierProvider(String key) {
        damageMultiplierProviders.remove(key);
    }

    /** Registers (or replaces) a keyed {@link StoreCategory} - the button it becomes always reflects live state since {@link StoreCategory#icon()} is called fresh on every render. */
    public void registerStoreCategory(StoreCategory category) {
        storeCategories.put(category.id(), category);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterStoreCategory(String id) {
        storeCategories.remove(id);
    }

    public StoreHubGui getStoreHubGui() {
        return storeHubGui;
    }

    /** The current global attack-speed multiplier - the product of every currently-registered provider (1.0 if none are registered). Shortens the tick interval between a pet's hits (see yield-zones' {@code PetCombatController}); higher is faster. */
    public double attackSpeedMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : attackSpeedMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Registers (or replaces) this plugin's own keyed attack-speed-multiplier contribution. */
    public void registerAttackSpeedMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        attackSpeedMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterAttackSpeedMultiplierProvider(String key) {
        attackSpeedMultiplierProviders.remove(key);
    }

    /** The current global Auto Mode switch-speed multiplier - the product of every currently-registered provider (1.0 if none are registered). See yield-zones' {@code PetCombatController}; higher is faster. */
    public double autoSwitchSpeedMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : autoSwitchSpeedMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Registers (or replaces) this plugin's own keyed auto-switch-speed-multiplier contribution. */
    public void registerAutoSwitchSpeedMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        autoSwitchSpeedMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterAutoSwitchSpeedMultiplierProvider(String key) {
        autoSwitchSpeedMultiplierProviders.remove(key);
    }

    /** The current global diamond multiplier - the product of every currently-registered provider (1.0 if none are registered). Apply to diamond payouts (see yield-zones' {@code OreCubeService}). */
    public double diamondMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : diamondMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Registers (or replaces) this plugin's own keyed diamond-multiplier contribution. */
    public void registerDiamondMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        diamondMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterDiamondMultiplierProvider(String key) {
        diamondMultiplierProviders.remove(key);
    }

    /** Registers an "apply this held item to a pet" gesture (see {@link PetItemHandler}) - tried in registration order by both {@code BagGui} and {@link PetItemFeedListener}. Candy registers its own here in {@link #onEnable}. */
    public void registerPetItemHandler(PetItemHandler handler) {
        petItemHandlers.add(handler);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterPetItemHandler(PetItemHandler handler) {
        petItemHandlers.remove(handler);
    }

    /** Tries every registered {@link PetItemHandler} in order - returns true (and stops) at the first one that recognizes/consumes {@code heldItem}. */
    public boolean applyPetItemHandlers(Player player, PetInstance pet, ItemStack heldItem) {
        for (PetItemHandler handler : petItemHandlers) {
            if (handler.apply(player, pet, heldItem)) {
                return true;
            }
        }
        return false;
    }
}
