package me.dontshare.yieldpacks.petenchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.item.ItemIconFactory;
import me.dontshare.yieldpacks.mastery.MasteryService;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.petenchant.PetEnchantContentLoader.PetEnchantContent;
import me.dontshare.yieldpacks.petenchant.PetEnchantService.CommonRoll;
import me.dontshare.yieldpacks.petenchant.PetEnchantService.RollResult;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * "The Enchanting Table" - a red-glass placeholder in the center slot until
 * a pet is loaded (see {@code PetEnchantSelectGui}), then an Enchant button
 * that spends diamonds, rolls (server-side, already decided - see {@link
 * PetEnchantService#rollFor}), plays a GUI-native "spin then land"
 * animation in that same center slot, and applies the result. Closing this
 * screen for any reason always drops whatever's loaded back to how it was
 * (re-equips if it came in equipped) - see the close handler in {@link
 * #open}.
 */
public final class PetEnchantTableGui {

    private static final int PET_SLOT = 13;
    private static final int ENCHANT_SLOT = 22;
    private static final int AUTO_SLOT = 4;
    private static final int CLOSE_SLOT = 31;
    private static final String ACCENT = "<#B15CFF>";
    private static final int ANIMATION_STEPS = 14;
    private static final long AUTO_ROLL_INTERVAL_TICKS = 4L;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final EquipmentService equipmentService;
    private final ItemIconFactory iconFactory;
    private final PetEnchantService enchantService;
    private final Supplier<PetEnchantContent> content;
    private final GuiManager guiManager;
    private final JavaPlugin plugin;
    private final MasteryService masteryService;
    private final Map<UUID, BukkitTask> autoTaskByPlayer = new HashMap<>();
    private PetEnchantSelectGui selectGui;
    private AutoEnchantGui autoEnchantGui;

    public PetEnchantTableGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                               Supplier<RarityRegistry> rarityRegistry, EquipmentService equipmentService,
                               ItemIconFactory iconFactory, PetEnchantService enchantService,
                               Supplier<PetEnchantContent> content, GuiManager guiManager, JavaPlugin plugin,
                               MasteryService masteryService) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.equipmentService = equipmentService;
        this.iconFactory = iconFactory;
        this.enchantService = enchantService;
        this.content = content;
        this.guiManager = guiManager;
        this.plugin = plugin;
        this.masteryService = masteryService;
    }

    /** Set once, right after both GUIs exist - see {@code PetEnchantSelectGui#setTableGui}'s own javadoc on this pattern. */
    public void setSelectGui(PetEnchantSelectGui selectGui) {
        this.selectGui = selectGui;
    }

    public void setAutoEnchantGui(AutoEnchantGui autoEnchantGui) {
        this.autoEnchantGui = autoEnchantGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        UUID loadedId = enchantService.currentlyLoaded(player);
        PetInstance loaded = loadedId != null ? profile.findPet(loadedId).orElse(null) : null;
        boolean autoRunning = isAutoRunning(player);

        var builder = Gui.builder(4, "Enchanting Table");
        builder.fill(IntStream.range(0, 36), GuiIcons.filler());
        builder.item(PET_SLOT, loaded != null ? buildPetIcon(profile, loaded) : buildEmptySlot(),
                (clicker, e) -> selectGui.open(clicker));
        builder.item(ENCHANT_SLOT, buildEnchantButton(profile, loaded),
                autoRunning ? null : (clicker, e) -> attemptEnchant(clicker));
        builder.item(AUTO_SLOT, buildAutoButton(player), (clicker, e) -> handleAutoButtonClick(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        Gui gui = builder.build();
        gui.setCloseHandler(closer -> {
            // Auto Enchant only ever runs while this exact screen is open -
            // leaving it any way (close button, Escape, opening a totally
            // different menu, which force-closes this one first) always
            // stops the loop rather than letting it keep spending diamonds
            // unattended in the background.
            cancelAutoTask(closer.getUniqueId());
            PackPlayerProfile closerProfile = store.getOrCreate(closer.getUniqueId());
            enchantService.clearSelection(closer, closerProfile);
            store.save(closer.getUniqueId());
        });
        guiManager.open(player, gui);
    }

    private void attemptEnchant(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        UUID loadedId = enchantService.currentlyLoaded(player);
        PetInstance pet = loadedId != null ? profile.findPet(loadedId).orElse(null) : null;
        if (pet == null) {
            player.sendMessage(Text.parse("<red>Select a pet first.</red>"));
            return;
        }
        if (!enchantService.canAfford(profile)) {
            player.sendMessage(Text.parse("<red>You need <cost> diamonds to enchant.</red>",
                    Placeholder.unparsed("cost", String.valueOf(enchantService.diamondCost()))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }

        enchantService.spend(profile);
        masteryService.grantXp(player, MasteryType.REFINERY, 1);
        RollResult result = enchantService.rollFor(pet);
        gui.set(ENCHANT_SLOT, buildEnchantButton(profile, pet), null); // grey out while rolling
        playRollAnimation(player, gui, 0, () -> {
            enchantService.apply(pet, result);
            store.save(player.getUniqueId());
            boolean unique = !result.uniques().isEmpty();
            player.playSound(player.getLocation(), unique ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    1f, unique ? 0.8f : 1.3f);
            if (unique) {
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.3);
            }
            // Update the ALREADY-OPEN gui's own slots in place rather than
            // calling open(player) again - that would build a brand new Gui
            // instance and implicitly close this one first (only one
            // inventory open at a time), which fires ITS close handler and
            // clears/re-equips the selected pet mid-session - exactly the
            // "roll again and it says nothing's selected" bug. Same "mutate
            // the live gui" idiom ForgeGui's own attemptForge already uses.
            refreshTableSlots(player);
        });
    }

    // --- Auto Enchant ---

    private boolean isAutoRunning(Player player) {
        return autoTaskByPlayer.containsKey(player.getUniqueId());
    }

    private void cancelAutoTask(UUID playerId) {
        BukkitTask task = autoTaskByPlayer.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void handleAutoButtonClick(Player player) {
        if (isAutoRunning(player)) {
            cancelAutoTask(player.getUniqueId());
            player.sendMessage(Text.parse("<gray>Auto Enchant stopped.</gray>"));
            refreshTableSlots(player);
        } else {
            autoEnchantGui.open(player);
        }
    }

    /** Validates (pet loaded, at least one target picked, can afford one roll) and either starts the loop or messages why not - called from {@link AutoEnchantGui}'s own Start button. */
    public void startAutoEnchant(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        UUID loadedId = enchantService.currentlyLoaded(player);
        PetInstance pet = loadedId != null ? profile.findPet(loadedId).orElse(null) : null;
        Set<String> targets = enchantService.autoTargets(player);
        if (pet == null) {
            player.sendMessage(Text.parse("<red>Select a pet first.</red>"));
            return;
        }
        if (targets.isEmpty()) {
            player.sendMessage(Text.parse("<red>Pick at least one enchant to hunt for first.</red>"));
            return;
        }
        if (!enchantService.canAfford(profile)) {
            player.sendMessage(Text.parse("<red>You need <cost> diamonds to start.</red>",
                    Placeholder.unparsed("cost", String.valueOf(enchantService.diamondCost()))));
            return;
        }

        open(player);
        player.sendMessage(Text.parse("<green>Auto Enchant started - hunting for a target enchant.</green>"));
        runAutoLoop(player, pet, targets);
    }

    /** Every how many rolls the loop below persists the profile and rebuilds the GUI's slots, rather than doing both on every single roll - a hunt for a rare Unique can run for dozens to hundreds of attempts at {@link #AUTO_ROLL_INTERVAL_TICKS} apart, and neither a DB write nor an icon/lore rebuild needs to happen 5x/sec for a hunt where only the final result actually matters visually. */
    private static final int AUTO_PERSIST_EVERY_N_ROLLS = 5;

    /** Rolls {@link #AUTO_ROLL_INTERVAL_TICKS} apart, skipping the full spin animation (too slow for a hunt that can take dozens of attempts) - a quick tick sound per attempt instead, and the normal landing flourish only once a target actually lands. Stops itself on: the loaded pet changing (swapped or unloaded), running out of diamonds, or a target Unique landing - {@link #open}'s own close handler is the other stop path (leaving the table at all). */
    private void runAutoLoop(Player player, PetInstance pet, Set<String> targets) {
        UUID playerId = player.getUniqueId();
        cancelAutoTask(playerId);
        int[] rollsSincePersist = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                // Must go through cancelAutoTask (not a bare map remove) -
                // this lambda can't reference its own "task" local (not yet
                // assigned at lambda-definition time), so the map is the
                // only remaining handle on the BukkitTask; removing the
                // entry without cancelling it orphans a live repeating task
                // that runs forever, un-cancellable, since nothing will
                // ever hold a reference to it again.
                cancelAutoTask(playerId);
                return;
            }
            PackPlayerProfile profile = store.getOrCreate(playerId);
            UUID loadedId = enchantService.currentlyLoaded(player);
            // currentlyLoaded() only checks the in-memory "selected" pointer,
            // not whether this exact PetInstance still exists in the
            // profile - an admin /admin packs reset (or any other bulk pet
            // wipe) clears profile.getPets() without touching that pointer,
            // which would otherwise leave this loop spending diamonds against an
            // orphaned pet object forever with zero effect and zero feedback.
            if (loadedId == null || !loadedId.equals(pet.getInstanceId()) || profile.findPet(pet.getInstanceId()).isEmpty()) {
                cancelAutoTask(playerId);
                player.sendMessage(Text.parse("<gray>Auto Enchant stopped - pet changed.</gray>"));
                refreshTableSlots(player);
                return;
            }
            if (!enchantService.canAfford(profile)) {
                cancelAutoTask(playerId);
                player.sendMessage(Text.parse("<red>Auto Enchant stopped - out of diamonds.</red>"));
                store.save(playerId);
                refreshTableSlots(player);
                return;
            }

            enchantService.spend(profile);
            RollResult result = enchantService.rollFor(pet);
            enchantService.apply(pet, result);
            rollsSincePersist[0]++;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);

            boolean matched = enchantService.rollMatchesAny(result, targets);
            if (matched) {
                cancelAutoTask(playerId);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
                player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.3);
                player.sendMessage(Text.parse("<green>Auto Enchant found a target enchant!</green>"));
            }
            // Persist and redraw only every AUTO_PERSIST_EVERY_N_ROLLS attempts
            // (or on this final, terminal roll) - every intermediate roll's
            // result already lives on the in-memory PetInstance the whole
            // loop shares, so nothing is lost by batching the save, and the
            // decoy-free auto rolls have no per-attempt visual anyway.
            // REFINERY mastery xp is granted here too (grantXp saves on its
            // own), batched to the SAME cadence rather than once per roll -
            // masteryService.grantXp would otherwise reintroduce exactly the
            // sustained per-roll DB save this batching was built to avoid.
            if (matched || rollsSincePersist[0] >= AUTO_PERSIST_EVERY_N_ROLLS) {
                masteryService.grantXp(player, MasteryType.REFINERY, rollsSincePersist[0]);
                rollsSincePersist[0] = 0;
                refreshTableSlots(player);
            }
        }, 0L, AUTO_ROLL_INTERVAL_TICKS);
        autoTaskByPlayer.put(playerId, task);
        refreshTableSlots(player);
    }

    /** Re-renders the pet/enchant/auto slots of whichever Gui the player currently has open, in place - a no-op if that isn't (or is no longer) this table (e.g. Auto Enchant's own background loop ticking after they've since navigated elsewhere, which the close handler already stops the loop for anyway). */
    private void refreshTableSlots(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        UUID loadedId = enchantService.currentlyLoaded(player);
        PetInstance pet = loadedId != null ? profile.findPet(loadedId).orElse(null) : null;
        boolean autoRunning = isAutoRunning(player);
        gui.set(PET_SLOT, pet != null ? buildPetIcon(profile, pet) : buildEmptySlot(), (clicker, e) -> selectGui.open(clicker));
        gui.set(ENCHANT_SLOT, buildEnchantButton(profile, pet), autoRunning ? null : (clicker, e) -> attemptEnchant(clicker));
        gui.set(AUTO_SLOT, buildAutoButton(player), (clicker, e) -> handleAutoButtonClick(clicker));
    }

    /** Self-rescheduling, ease-out "spin then land" - see the plan's own note on why this is a plain GUI-slot cycle rather than {@code PackRevealAnimationService}'s full 3D packet reel. */
    private void playRollAnimation(Player player, Gui gui, int step, Runnable onDone) {
        if (!player.isOnline()) {
            return;
        }
        if (step >= ANIMATION_STEPS) {
            onDone.run();
            return;
        }
        gui.set(PET_SLOT, buildDecoyIcon(), null);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f + step * 0.03f);
        // Ease-out: each step waits a little longer than the last, so the
        // spin visibly decelerates into its landing rather than stopping abruptly.
        long delayTicks = 1 + (step / 2);
        Bukkit.getScheduler().runTaskLater(plugin, () -> playRollAnimation(player, gui, step + 1, onDone), delayTicks);
    }

    private ItemStack buildDecoyIcon() {
        PetEnchantContent c = content.get();
        boolean pickUnique = !c.uniques().isEmpty() && ThreadLocalRandom.current().nextInt(6) == 0;
        if (pickUnique) {
            PetUniqueDefinition unique = c.uniques().get(ThreadLocalRandom.current().nextInt(c.uniques().size()));
            ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                    .name("<" + unique.colorHex() + "><bold>" + unique.displayName().toUpperCase(Locale.ROOT) + "</bold>");
            return builder.hideAttributes().build();
        }
        List<PetEnchantType> types = List.of(PetEnchantType.values());
        PetEnchantType type = types.get(ThreadLocalRandom.current().nextInt(types.size()));
        var ladder = c.commons().get(type);
        int level = ladder != null && !ladder.values().isEmpty() ? ThreadLocalRandom.current().nextInt(ladder.values().size()) : 0;
        ItemBuilder builder = ItemBuilder.of(Material.BOOK)
                .name(ACCENT + "<bold>" + type.displayName().toUpperCase(Locale.ROOT) + " " + Formatting.toRoman(level + 1) + "</bold>");
        return builder.hideAttributes().build();
    }

    private ItemStack buildEmptySlot() {
        ItemBuilder builder = ItemBuilder.of(Material.RED_STAINED_GLASS_PANE).name("&c&lNO PET SELECTED");
        MenuLore.info("enchanting table", List.of(" &7Click to select a pet", " &7from your collection."), ACCENT, List.of())
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildPetIcon(PackPlayerProfile profile, PetInstance pet) {
        ItemDefinition item = itemRegistry.get().find(pet.getItemId()).orElse(null);
        if (item == null) {
            return buildEmptySlot();
        }
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String accent = rarity != null ? "<" + rarity.colorHex() + ">" : "<#FFFFFF>";

        List<String> data = new java.util.ArrayList<>();
        data.add("&7Rarity: " + (rarity != null ? rarity.displayName() : "&7Unknown"));
        data.add("&7Damage: &4❤&c" + Formatting.format(equipmentService.effectiveDamage(profile, pet)));
        PetEnchantLore.appendEnchantLines(data, pet, content.get());

        ItemBuilder builder = iconFactory.baseIcon(item)
                .name(MenuLore.buttonName(accent, Formatting.stripLeadingColorCodes(item.displayName()).toUpperCase(Locale.ROOT)));
        MenuLore.button("enchanting table", List.of(), accent, data, "Click to Swap Pet").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildEnchantButton(PackPlayerProfile profile, PetInstance loaded) {
        boolean hasPet = loaded != null;
        boolean canAfford = enchantService.canAfford(profile);
        boolean enabled = hasPet && canAfford;
        ItemBuilder builder = ItemBuilder.of(Material.ENCHANTED_BOOK)
                .name(enabled ? MenuLore.buttonName(ACCENT, "ENCHANT") : "&7&lENCHANT");
        String callToAction = !hasPet ? "Select a Pet First" : !canAfford ? "Not Enough Diamonds" : "Click to Enchant";
        MenuLore.button(
                "enchanting table",
                List.of(" &7Rolls a fresh set of enchants", " &7for the selected pet.", " &cExisting enchants will be replaced."),
                ACCENT,
                List.of("Cost: &b" + Formatting.format((double) enchantService.diamondCost()) + " Diamonds"),
                callToAction
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildAutoButton(Player player) {
        boolean running = isAutoRunning(player);
        ItemBuilder builder = ItemBuilder.of(running ? Material.BARRIER : Material.COMPASS)
                .name(running ? "<red><bold>STOP AUTO ENCHANT</bold></red>" : MenuLore.buttonName(ACCENT, "AUTO ENCHANT"));
        if (running) {
            MenuLore.button("enchanting table", List.of(" &7Rolling automatically until", " &7a target enchant lands."),
                    "<red>", "Click to Stop").forEach(builder::lore);
        } else {
            MenuLore.button("enchanting table",
                    List.of(" &7Pick target enchants and roll", " &7automatically until you land", " &7one (or run out of diamonds)."),
                    ACCENT, "Click to Configure").forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }
}
