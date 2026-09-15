package me.dontshare.yieldmining;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldmining.data.MiningProfile;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldmining.command.MiningAdminCommand;
import me.dontshare.yieldmining.data.MiningContent;
import me.dontshare.yieldmining.data.MiningContentLoader;
import me.dontshare.yieldmining.data.MiningSpot;
import me.dontshare.yieldmining.enchant.PickaxeCommand;
import me.dontshare.yieldmining.enchant.PickaxeEnchantContentLoader;
import me.dontshare.yieldmining.enchant.PickaxeEnchantDefinition;
import me.dontshare.yieldmining.enchant.PickaxeEnchantMenuGui;
import me.dontshare.yieldmining.enchant.PickaxeEnchantService;
import me.dontshare.yieldmining.enchant.PickaxeMenuListener;
import me.dontshare.yieldmining.enchant.PickaxeUpgradeGui;
import me.dontshare.yieldmining.forge.ForgeBoostService;
import me.dontshare.yieldmining.forge.ForgeCommand;
import me.dontshare.yieldmining.forge.ForgeContentLoader;
import me.dontshare.yieldmining.forge.ForgeGui;
import me.dontshare.yieldmining.forge.ForgeStatType;
import me.dontshare.yieldmining.forge.ForgedItem;
import me.dontshare.yieldmining.forge.SpecialOreItem;
import me.dontshare.yieldmining.forge.SpecialOreTier;
import me.dontshare.yieldmining.orebag.OreBagCommand;
import me.dontshare.yieldmining.orebag.OreBagGui;
import me.dontshare.yieldmining.orebag.OreBagService;
import me.dontshare.yieldmining.orebag.OreIndexCommand;
import me.dontshare.yieldmining.orebag.OreIndexGui;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.pet.PetItemHandler;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class YieldMining extends JavaPlugin {

    private static final String PROVIDER_KEY = "forge";

    private MiningContentLoader contentLoader;
    private volatile MiningContent content;
    private PlayerDataStore<MiningProfile> miningStore;
    private MiningService miningService;
    private MiningItem miningItem;

    private PickaxeEnchantContentLoader enchantContentLoader;
    private volatile Map<String, PickaxeEnchantDefinition> enchantContent;
    private PickaxeEnchantService enchantService;

    private ForgeContentLoader forgeContentLoader;
    private volatile List<SpecialOreTier> forgeTiers;
    private ForgeBoostService forgeBoostService;
    private ForgedItem forgedItem;
    /** Registered/unregistered as the exact same instance both times - two separately-created method references aren't guaranteed equals(), so List#remove in unregisterPetItemHandler would silently no-op otherwise. */
    private PetItemHandler forgedItemHandler;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new MiningContentLoader(this, getLogger());
        // Safe empty default until the deferred load below runs - same
        // reasoning every other satellite plugin this session uses.
        content = new MiningContent(Map.of(), List.of());

        enchantContentLoader = new PickaxeEnchantContentLoader(this, getLogger());
        enchantContent = Map.of();
        miningStore = PlayerStores.register(this, core.getListenerManager(), core.getDatabaseManager(),
                "mining", MiningProfile.class, MiningProfile::new, "mining data");
        enchantService = new PickaxeEnchantService(() -> enchantContent, packs.getPlayerStore(), miningStore);

        forgeContentLoader = new ForgeContentLoader(this, getLogger());
        forgeTiers = List.of();
        SpecialOreItem specialOreItem = new SpecialOreItem(this);
        forgedItem = new ForgedItem(this);
        forgeBoostService = new ForgeBoostService(packs.getPlayerStore());
        registerForgeProviders(packs);

        OreBagService oreBagService = new OreBagService(miningStore, specialOreItem, () -> forgeTiers);

        miningItem = new MiningItem(this);
        miningService = new MiningService(this, () -> content, packs, miningItem, this::onSpotCreated, enchantService,
                () -> forgeTiers, specialOreItem, oreBagService);
        Bukkit.getPluginManager().registerEvents(miningService, this);

        PickaxeEnchantMenuGui enchantMenuGui = new PickaxeEnchantMenuGui(packs, enchantService, core.getGuiManager());
        PickaxeUpgradeGui upgradeGui = new PickaxeUpgradeGui(packs, enchantService, core.getGuiManager(), enchantMenuGui);
        enchantMenuGui.setUpgradeGui(upgradeGui);
        Bukkit.getPluginManager().registerEvents(new PickaxeMenuListener(upgradeGui), this);
        CommandManager.register(this, PickaxeCommand.build(upgradeGui), "Open the pickaxe enchant menu");

        ForgeGui forgeGui = new ForgeGui(core.getGuiManager(), specialOreItem, forgedItem);
        CommandManager.register(this, ForgeCommand.build(forgeGui), "Combine Special Ore into a held item");

        OreBagGui oreBagGui = new OreBagGui(packs.getPlayerStore(), miningStore, oreBagService, core.getGuiManager());
        CommandManager.register(this, OreBagCommand.build(oreBagGui), "View your Ore Bag");
        OreIndexGui oreIndexGui = new OreIndexGui(() -> content, miningStore, core.getGuiManager());
        CommandManager.register(this, OreIndexCommand.build(oreIndexGui), "Browse the Ore Index");

        forgedItemHandler = this::applyForgedItem;
        packs.registerPetItemHandler(forgedItemHandler);

        core.getAdminCommandRegistry().register(MiningAdminCommand.build(this));

        Bukkit.getScheduler().runTask(this, this::reloadContent);
    }

    @Override
    public void onDisable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        if (packs != null) {
            packs.unregisterCoinMultiplierProvider(PROVIDER_KEY);
            packs.unregisterDiamondMultiplierProvider(PROVIDER_KEY);
            packs.unregisterAttackSpeedMultiplierProvider(PROVIDER_KEY);
            packs.getLuckService().unregisterExtraLuckProvider(PROVIDER_KEY);
            if (forgedItemHandler != null) {
                packs.unregisterPetItemHandler(forgedItemHandler);
            }
        }
    }

    private void registerForgeProviders(YieldPacks packs) {
        packs.registerCoinMultiplierProvider(PROVIDER_KEY, forgeBoostService::coinMultiplier);
        packs.registerDiamondMultiplierProvider(PROVIDER_KEY, forgeBoostService::diamondMultiplier);
        packs.registerAttackSpeedMultiplierProvider(PROVIDER_KEY, forgeBoostService::attackSpeedMultiplier);
        packs.getLuckService().registerExtraLuckProvider(PROVIDER_KEY, forgeBoostService::luckBonus);
    }

    /**
     * A {@code PetItemHandler} (see yield-packs' registry) - applying a
     * forged held item works identically whether the target pet is
     * equipped, bagged, or withdrawn, since {@code BagGui}/{@code
     * PetItemFeedListener} both resolve a real {@code PetInstance} before
     * calling this either way. Consumes 1 unit and returns true if
     * recognized; false (no side effects) for anything else in hand.
     */
    private boolean applyForgedItem(Player player, PetInstance pet, ItemStack item) {
        ForgeStatType type = forgedItem.statOf(item);
        if (type == null) {
            return false;
        }
        double multiplier = forgedItem.multiplierOf(item);
        double newTotal = forgeBoostService.consume(player, pet, type, multiplier);
        item.setAmount(item.getAmount() - 1);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        player.sendMessage(Text.parse(
                "<green>Applied! This pet's permanent <type> bonus is now <total>x.</green>",
                Placeholder.unparsed("type", type.name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("total", String.format(Locale.ROOT, "%.3f", newTotal))));
        return true;
    }

    /** Re-reads mining.yml + mining-spots.yml + pickaxe-enchants.yml + special-ore-tiers.yml, then re-syncs every online player's view of every spot and re-caches their enchant rolls. */
    public void reloadContent() {
        content = contentLoader.load();
        miningService.repaintAllForEveryone();

        enchantContent = enchantContentLoader.load();
        forgeTiers = forgeContentLoader.load();

        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            var profile = packs.getPlayerStore().getCached(player.getUniqueId());
            if (profile != null) {
                enchantService.recompute(profile);
            }
        }
    }

    public MiningContent getContent() {
        return content;
    }

    public MiningItem getMiningItem() {
        return miningItem;
    }

    /** Persists a brand-new spot (created by placing a MiningItem), reloads content, then shows it to everyone already online. */
    private void onSpotCreated(MiningSpot spot) {
        contentLoader.saveSpot(spot);
        content = contentLoader.load();
        miningService.paintAndRegisterForAll(spot);
    }

    /** Removes whichever known spot sits closest to {@code near}, within {@code radius} blocks - or null if none are that close. */
    public MiningSpot removeNearestSpot(org.bukkit.Location near, double radius) {
        MiningSpot closest = null;
        double closestDistanceSquared = radius * radius;
        for (MiningSpot spot : content.spots()) {
            if (!spot.world().equals(near.getWorld())) {
                continue;
            }
            double dx = spot.x() + 0.5 - near.getX();
            double dy = spot.y() + 0.5 - near.getY();
            double dz = spot.z() + 0.5 - near.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= closestDistanceSquared) {
                closestDistanceSquared = distanceSquared;
                closest = spot;
            }
        }
        if (closest == null) {
            return null;
        }
        contentLoader.removeSpot(closest.world(), closest.x(), closest.y(), closest.z());
        content = contentLoader.load();
        miningService.clearSpotForAll(closest);
        return closest;
    }
}
