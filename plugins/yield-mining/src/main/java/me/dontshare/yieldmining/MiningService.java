package me.dontshare.yieldmining;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import me.dontshare.yieldcore.fakeblock.FakeBlockDigRegistry;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldmining.data.MiningContent;
import me.dontshare.yieldmining.data.MiningSpot;
import me.dontshare.yieldmining.data.OreDefinition;
import me.dontshare.yieldmining.enchant.MiningRewardType;
import me.dontshare.yieldmining.enchant.PickaxeEnchantService;
import me.dontshare.yieldmining.event.OreMinedEvent;
import me.dontshare.yieldmining.forge.SpecialOreItem;
import me.dontshare.yieldmining.forge.SpecialOreTier;
import me.dontshare.yieldmining.orebag.OreBagService;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fully client-sided ore mining - a "mining spot" is never a real world
 * block. An admin places a tagged {@link MiningItem} (see {@link #onPlace})
 * to create one; from then on it's rendered to every online player purely
 * via {@link Player#sendBlockChange}, and a real hold-to-mine dig is
 * detected via {@link FakeBlockDigRegistry} (raw digging packets), not a
 * real {@code BlockBreakEvent} - there is no real block for one to fire on.
 * <p>
 * Breaking is still personal: it fakes the position to {@code AIR} for just
 * the player who mined it, and restores it for just them after a regen
 * timer - every other player keeps seeing (and can independently mine) the
 * same spot on their own timer, exactly like Hypixel Skyblock's Foraging.
 * <p>
 * Anti-exploit: a per-player "currently broken for them" set blocks a
 * spoofed digging packet from re-triggering the reward before that
 * player's own regen timer completes.
 */
public final class MiningService implements Listener {

    private final JavaPlugin plugin;
    private final Supplier<MiningContent> content;
    private final YieldPacks packs;
    private final MiningItem miningItem;
    private final Consumer<MiningSpot> onSpotCreated;
    private final PickaxeEnchantService enchantService;
    private final Supplier<List<SpecialOreTier>> forgeTiers;
    private final SpecialOreItem specialOreItem;
    private final OreBagService oreBagService;

    /** Player -> set of spot keys currently hidden (faked to AIR) for just that player. */
    private final Map<UUID, Set<String>> brokenForPlayer = new HashMap<>();
    /** Player -> spot key -> the pending regen task, so a quitting player's timers can be cancelled outright. */
    private final Map<UUID, Map<String, BukkitTask>> regenTasks = new HashMap<>();

    public MiningService(JavaPlugin plugin, Supplier<MiningContent> content, YieldPacks packs,
                          MiningItem miningItem, Consumer<MiningSpot> onSpotCreated, PickaxeEnchantService enchantService,
                          Supplier<List<SpecialOreTier>> forgeTiers, SpecialOreItem specialOreItem, OreBagService oreBagService) {
        this.plugin = plugin;
        this.content = content;
        this.packs = packs;
        this.miningItem = miningItem;
        this.onSpotCreated = onSpotCreated;
        this.enchantService = enchantService;
        this.forgeTiers = forgeTiers;
        this.specialOreItem = specialOreItem;
        this.oreBagService = oreBagService;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Material oreMaterial = miningItem.materialOf(event.getItemInHand());
        if (oreMaterial == null) {
            return;
        }
        // Never a real block - the whole point is that this is packet-only.
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!content.get().ores().containsKey(oreMaterial)) {
            player.sendMessage(Text.parse("<red>'" + oreMaterial.name() + "' isn't a configured ore type anymore - ask an admin to check mining.yml.</red>"));
            return;
        }

        Location loc = event.getBlockPlaced().getLocation();
        consumeOne(player, event.getHand());

        MiningSpot spot = new MiningSpot(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), oreMaterial);
        onSpotCreated.accept(spot);
        player.sendMessage(Text.parse("<green>Created a mining spot - minable by everyone.</green>"));
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        PlayerInventory inventory = player.getInventory();
        ItemStack item = hand == EquipmentSlot.OFF_HAND ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
        item.setAmount(item.getAmount() - 1);
        if (hand == EquipmentSlot.OFF_HAND) {
            inventory.setItemInOffHand(item);
        } else {
            inventory.setItemInMainHand(item);
        }
    }

    private void onFinishedDigging(Player player, MiningSpot spot) {
        OreDefinition definition = content.get().ores().get(spot.material());
        if (definition == null) {
            // mining.yml no longer configures this material - spot sits inert until it's reconfigured or removed.
            return;
        }

        UUID playerId = player.getUniqueId();
        String spotKey = spot.key();
        Set<String> alreadyBroken = brokenForPlayer.computeIfAbsent(playerId, id -> new HashSet<>());
        if (!alreadyBroken.add(spotKey)) {
            // Already faked broken for this player (a spoofed dig packet, most likely) - ignore.
            return;
        }

        Location loc = new Location(spot.world(), spot.x(), spot.y(), spot.z());
        player.sendBlockChange(loc, Material.AIR.createBlockData());

        PackPlayerProfile profile = packs.getPlayerStore().getCached(playerId);

        double oreAmountBoost = profile != null ? enchantService.rollMultiplier(profile, MiningRewardType.ORE_AMOUNT) : 1.0;
        giveDrop(player, definition, oreAmountBoost, profile);

        long coins = 0;
        if (profile != null && definition.coinReward() > 0) {
            double coinBoost = enchantService.rollMultiplier(profile, MiningRewardType.COINS);
            coins = Math.round(definition.coinReward() * packs.coinMultiplier(profile) * coinBoost);
            profile.setCoins(profile.getCoins().add(BigInteger.valueOf(coins)));
            packs.getPlayerStore().save(playerId);
        }
        if (profile != null) {
            packs.getMasteryService().grantXp(player, MasteryType.MINING, 1);
        }

        int xp = definition.xp();
        if (profile != null && xp > 0) {
            xp = (int) Math.round(xp * enchantService.rollMultiplier(profile, MiningRewardType.XP));
        }

        Bukkit.getPluginManager().callEvent(new OreMinedEvent(player, spot.material(), coins, xp));
        player.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        MiningAbsorbAnimation.play(plugin, player, loc, spot.material());

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendBlockChange(loc, spot.material().createBlockData());
            }
            Set<String> stillBroken = brokenForPlayer.get(playerId);
            if (stillBroken != null) {
                stillBroken.remove(spotKey);
            }
            Map<String, BukkitTask> tasks = regenTasks.get(playerId);
            if (tasks != null) {
                tasks.remove(spotKey);
            }
        }, definition.regenTicks());
        regenTasks.computeIfAbsent(playerId, id -> new HashMap<>()).put(spotKey, task);
    }

    /** The actual point of mining - a real, physical item, not just a number going up. Overflow drops at the player's feet instead of vanishing if their inventory is full. A Special Ore result never touches the inventory at all - it routes straight into the Ore Bag (see OreBagService), falling back to a normal inventory item only if no cached profile exists to route it to. */
    private void giveDrop(Player player, OreDefinition definition, double amountBoost, PackPlayerProfile profile) {
        if (definition.dropMaterial() == null) {
            return;
        }
        int baseAmount = definition.dropMin() == definition.dropMax()
                ? definition.dropMin()
                : ThreadLocalRandom.current().nextInt(definition.dropMin(), definition.dropMax() + 1);
        int amount = Math.max(1, (int) Math.round(baseAmount * amountBoost));

        SpecialOreTier tier = rollSpecialOreTier();
        int normalAmount = tier == null ? amount : amount - 1;
        if (normalAmount > 0) {
            giveItem(player, OreDropItemFactory.create(definition.dropMaterial(), normalAmount));
        }
        if (tier != null) {
            double multiplier = tier.multiplierMin() == tier.multiplierMax()
                    ? tier.multiplierMin()
                    : ThreadLocalRandom.current().nextDouble(tier.multiplierMin(), tier.multiplierMax());
            if (profile != null) {
                oreBagService.add(profile, player, definition.dropMaterial(), multiplier, tier);
            } else {
                giveItem(player, specialOreItem.create(definition.dropMaterial(), multiplier, tier));
            }
        }
    }

    /** Checks every configured tier rarest-first, independently - the rarest tier that hits wins, so a lucky roll never gets downgraded to a common result just because that check also would have passed. Null if nothing hits. */
    private SpecialOreTier rollSpecialOreTier() {
        for (SpecialOreTier tier : forgeTiers.get()) {
            if (ThreadLocalRandom.current().nextInt(tier.oneIn()) == 0) {
                return tier;
            }
        }
        return null;
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (MiningSpot spot : content.get().spots()) {
            paintAndRegister(player, spot);
        }
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile != null) {
            enchantService.recompute(profile);
        }
    }

    /** sendBlockChange is silently dropped if the target chunk isn't loaded on the client yet - see ZoneLockService#onChunkLoad for the identical fix applied to zone walls. */
    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (MiningSpot spot : content.get().spots()) {
            if (!spot.world().equals(event.getChunk().getWorld())) {
                continue;
            }
            if ((spot.x() >> 4) != chunkX || (spot.z() >> 4) != chunkZ) {
                continue;
            }
            paintAndRegister(player, spot);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Map<String, BukkitTask> tasks = regenTasks.remove(playerId);
        if (tasks != null) {
            tasks.values().forEach(BukkitTask::cancel);
        }
        brokenForPlayer.remove(playerId);
        enchantService.clearCache(playerId);
    }

    /** Paints and registers one spot for one player - the initial "show them a spot they didn't know about yet" case (join/chunk-load). */
    private void paintAndRegister(Player player, MiningSpot spot) {
        Location loc = new Location(spot.world(), spot.x(), spot.y(), spot.z());
        player.sendBlockChange(loc, spot.material().createBlockData());
        FakeBlockDigRegistry.register(player, loc, p -> onFinishedDigging(p, spot));
    }

    /** Call right after a brand-new spot is persisted - shows it to everyone already online immediately, rather than waiting for their next join/chunk-load. */
    public void paintAndRegisterForAll(MiningSpot spot) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            paintAndRegister(player, spot);
        }
    }

    /** Call after a spot is removed - restores the real (untouched) block for everyone and stops treating digs there as mining. */
    public void clearSpotForAll(MiningSpot spot) {
        Location loc = new Location(spot.world(), spot.x(), spot.y(), spot.z());
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
            FakeBlockDigRegistry.unregister(player, loc);
        }
    }

    /** Call after a content reload - re-syncs every online player's view of every currently-configured spot (e.g. a material swapped in mining-spots.yml). */
    public void repaintAllForEveryone() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (MiningSpot spot : content.get().spots()) {
                paintAndRegister(player, spot);
            }
        }
    }
}
