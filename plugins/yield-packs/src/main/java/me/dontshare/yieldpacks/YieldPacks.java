package me.dontshare.yieldpacks;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.command.BagCommand;
import me.dontshare.yieldpacks.command.IndexCommand;
import me.dontshare.yieldpacks.command.PacksAdminCommand;
import me.dontshare.yieldpacks.command.PacksCommand;
import me.dontshare.yieldpacks.command.PetVisibilityCommand;
import me.dontshare.yieldpacks.command.RollAnimationCommand;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.dialog.QuantityPickerDialog;
import me.dontshare.yieldpacks.display.PetDisplayConfig;
import me.dontshare.yieldpacks.display.PetDisplayConfigLoader;
import me.dontshare.yieldpacks.display.PetDisplayListener;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.economy.IncomeService;
import me.dontshare.yieldpacks.economy.LuckService;
import me.dontshare.yieldpacks.gui.BagGui;
import me.dontshare.yieldpacks.gui.IndexGui;
import me.dontshare.yieldpacks.gui.PackShopGui;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfileManager;
import me.dontshare.yieldpacks.roll.ExistsCounterStore;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpacks.roll.RollAnimationService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
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

    /**
     * Feeds IncomeService's coin multiplier. Defaults to a flat 1.0 - if
     * yield-rebirth is installed, it registers its own rebirth-based
     * multiplier here on enable (and clears it back to 1.0 on disable) via
     * {@link #setCoinMultiplierProvider}, so yield-packs never needs a
     * compile-time dependency on yield-rebirth.
     */
    private volatile Function<PackPlayerProfile, Double> coinMultiplierProvider = profile -> 1.0;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);

        contentLoader = new PackContentLoader(this, getLogger());
        content = contentLoader.load();

        playerStore = new PlayerDataStore<>(core.getDatabaseManager(), "playerData", "packs",
                PackPlayerProfile.class, PackPlayerProfile::new, getLogger());
        profileManager = new PackPlayerProfileManager(playerStore, getLogger());
        core.getListenerManager().register(profileManager);
        playerStore.startAutoSave(this, AUTOSAVE_INTERVAL_TICKS);

        equipmentService = new EquipmentService(() -> content.items(), EQUIP_CAP);
        luckService = new LuckService(() -> content.packs());

        ExistsCounterStore existsCounterStore = new ExistsCounterStore(core.getDatabaseManager(), getLogger());
        existsCounterStore.loadAll();

        ItemIconFactory iconFactory = new ItemIconFactory();
        petDisplayConfigLoader = new PetDisplayConfigLoader(this);
        PetDisplayConfig displayConfig = petDisplayConfigLoader.load();
        petDisplayService = new PetDisplayService(this, playerStore, () -> content.items(), () -> content.rarities(),
                iconFactory, displayConfig);
        petDisplayService.start();
        core.getListenerManager().register(new PetDisplayListener(petDisplayService));

        PackRollService rollService = new PackRollService(() -> content, playerStore, equipmentService, luckService,
                existsCounterStore, petDisplayService);

        IncomeService incomeService = new IncomeService(this, playerStore, () -> content.items(),
                profile -> coinMultiplierProvider.apply(profile));
        incomeService.start();

        core.getPlaceholderRegistry().register("coins", player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            return profile != null ? Formatting.spaced(profile.getCoins()) : "0";
        });
        core.getPlaceholderRegistry().register("gems", player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            return profile != null ? Formatting.spaced(profile.getGems()) : "0";
        });
        core.getPlaceholderRegistry().register("income", player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            return profile != null ? Formatting.format(incomeService.currentPerSecond(profile)) : "0";
        });
        core.getScoreboardDisplay().addLineProvider(player -> {
            PackPlayerProfile profile = playerStore.getCached(player.getUniqueId());
            if (profile == null) {
                return List.of();
            }
            return List.of(
                    " ",
                    "&f$" + Formatting.spaced(profile.getCoins()) + " &7Coins",
                    "&a+$" + Formatting.format(incomeService.currentPerSecond(profile)) + "&7/sec",
                    "&b" + Formatting.spaced(profile.getGems()) + " &7Gems"
            );
        });

        RollAnimationService animationService = new RollAnimationService(() -> content.rarities());
        QuantityPickerDialog quantityPickerDialog = new QuantityPickerDialog(rollService, playerStore, animationService);
        PackShopGui packShopGui = new PackShopGui(() -> content, core.getGuiManager(), quantityPickerDialog);
        BagGui bagGui = new BagGui(playerStore, () -> content.items(), () -> content.rarities(), equipmentService,
                core.getGuiManager(), iconFactory, petDisplayService);
        IndexGui indexGui = new IndexGui(() -> content, playerStore, luckService, core.getGuiManager(), packShopGui);

        CommandManager.register(this, PacksCommand.build(packShopGui), "Open the packs shop", List.of());
        CommandManager.register(this, BagCommand.build(bagGui), "Open your bag", List.of());
        CommandManager.register(this, IndexCommand.build(indexGui), "Open your collection index", List.of());
        CommandManager.register(this, PacksAdminCommand.build(this), "Yield Packs admin commands", List.of());
        CommandManager.register(this, RollAnimationCommand.build(playerStore), "Toggle the pack-opening roll animation", List.of());
        CommandManager.register(this, PetVisibilityCommand.build(playerStore, petDisplayService),
                "Set what equipped-pet displays you personally see", List.of());
    }

    @Override
    public void onDisable() {
        if (petDisplayService != null) {
            petDisplayService.shutdown();
        }
        if (profileManager != null) {
            profileManager.getStore().saveAllSync();
        }
    }

    /** Re-reads packs.yml and pet-display.yml, atomically swapping in the new registries/config - no restart needed. */
    public void reloadContent() {
        content = contentLoader.load();
        if (petDisplayService != null) {
            petDisplayService.setConfig(petDisplayConfigLoader.load());
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

    public PetDisplayService getPetDisplayService() {
        return petDisplayService;
    }

    /** Pass null to fall back to the default flat 1.0 multiplier (e.g. when yield-rebirth is disabling). */
    public void setCoinMultiplierProvider(Function<PackPlayerProfile, Double> provider) {
        this.coinMultiplierProvider = provider != null ? provider : (profile -> 1.0);
    }
}
