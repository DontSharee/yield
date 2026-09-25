package me.dontshare.yieldzones.zone;

import me.dontshare.yieldcore.fakeblock.FakeBlockClickRegistry;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.data.ZoneRegion;
import me.dontshare.yieldzones.data.ZoneUnlockCost;
import me.dontshare.yieldzones.data.ZoneWall;
import me.dontshare.yieldzones.event.ZoneUnlockedEvent;
import me.dontshare.yieldzones.gui.ZonePurchaseGui;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Everything about a zone actually BEING locked: whether a given player has
 * it, the fake {@code TINTED_GLASS}-by-default wall shown to everyone who
 * doesn't (rendered/restored per-viewer via plain {@link Player#sendBlockChange}
 * loops - there's no bulk block-change precedent in this codebase, and a
 * wall is small enough that looping is entirely fine), the server-side
 * {@link PlayerMoveEvent} backstop that blocks actually walking into a
 * locked zone's real region (the fake wall already stops a normal client
 * cold, but that's client-side rendering only - this is what stops someone
 * bypassing it), and the actual purchase transaction a {@code ZonePurchaseGui}
 * click runs through {@link #attemptPurchase}.
 * <p>
 * The purchase GUI itself is wired in after construction via
 * {@link #setPurchaseGui} (mirrors {@code OreCubeService#setClickHandler}'s
 * own setter-injection shape) - {@code ZonePurchaseGui} needs this service
 * for the actual purchase logic, and this service needs the GUI to react to
 * a wall click/bump, so neither can be a constructor parameter of the other.
 */
public final class ZoneLockService implements Listener {

    public enum PurchaseResult { ALREADY_UNLOCKED, INSUFFICIENT_FUNDS, MISSING_ITEMS, SUCCESS }

    /** How far from a locked zone's wall a player needs to be before it's rendered/registered for them at all. */
    private static final double WALL_RENDER_RADIUS = 48.0;
    /** How close a player needs to walk up to a locked wall before the purchase GUI auto-pops for them. */
    private static final double AUTO_POPUP_RADIUS = 3.5;
    /** How close a player has to be to actually click a wall block and open the purchase GUI - deliberately short, not the ore-cube-click range, since a wall spans many blocks and clicking it from across the map felt wrong. */
    private static final double WALL_CLICK_RANGE = 3.0;
    // 4 ticks (0.2s), not the slower 0.5s this originally shipped with - at
    // normal sprint speed a player can cover more than the gap between two
    // 0.5s checks in one step, so a quick bump-into-then-back-off-the-wall
    // could land entirely between two polls and never trigger the popup.
    private static final long TICK_INTERVAL = 4L;
    private static final long AUTO_POPUP_COOLDOWN_MILLIS = 8_000L;
    public static final String BYPASS_PERMISSION = "yieldzones.bypasslock";

    private static final long DENIAL_MESSAGE_INTERVAL_MILLIS = 2000L;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final Supplier<Map<String, ZoneDefinition>> zones;

    // Which locked zones currently have their wall actually rendered for a given viewer - so it's only shown/hidden once per state change, not re-sent every tick.
    private final Map<UUID, Set<String>> wallShownFor = new ConcurrentHashMap<>();
    /**
     * Extra conditions on entering a zone, beyond its own unlock cost -
     * keyed and composable like every other provider registry here.
     * <p>
     * A zone can be closed for reasons that have nothing to do with whether
     * a player has paid for it: the Haunted Hollow exists only while its
     * event is running (see yield-events). yield-zones has no business
     * knowing what a seasonal event is, so it asks rather than decides.
     */
    private final Map<String, AccessGate> accessGates = new ConcurrentHashMap<>();
    /** When each player was last told why a zone turned them away, so a gate cannot spam on a movement event. */
    private final Map<UUID, Long> lastDenialAt = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> lastAutoPopupAt = new ConcurrentHashMap<>();

    private ZonePurchaseGui purchaseGui;

    public ZoneLockService(JavaPlugin plugin, YieldPacks packs, Supplier<Map<String, ZoneDefinition>> zones) {
        this.plugin = plugin;
        this.packs = packs;
        this.zones = zones;
    }

    public void setPurchaseGui(ZonePurchaseGui purchaseGui) {
        this.purchaseGui = purchaseGui;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("zones.locks", this::tick), 0L, TICK_INTERVAL);
    }

    public boolean isUnlocked(Player player, ZoneDefinition zone) {
        if (zone.unlockCost().isFree()) {
            return true;
        }
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        return profile != null && profile.getUnlockedZoneIds().contains(zone.id());
    }

    /** Deducts the zone's cost and grants access - callers must already know the zone isn't free (see {@link #isUnlocked}). */
    public PurchaseResult attemptPurchase(Player player, ZoneDefinition zone) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        if (isUnlocked(player, zone)) {
            return PurchaseResult.ALREADY_UNLOCKED;
        }

        ZoneUnlockCost cost = zone.unlockCost();
        if (profile.getCoins().compareTo(cost.coins()) < 0 || profile.getDiamonds().compareTo(cost.diamonds()) < 0) {
            return PurchaseResult.INSUFFICIENT_FUNDS;
        }
        for (ZoneUnlockCost.ItemCost itemCost : cost.items()) {
            if (countInInventory(player, itemCost) < itemCost.amount()) {
                return PurchaseResult.MISSING_ITEMS;
            }
        }

        profile.setCoins(profile.getCoins().subtract(cost.coins()));
        profile.setDiamonds(profile.getDiamonds().subtract(cost.diamonds()));
        for (ZoneUnlockCost.ItemCost itemCost : cost.items()) {
            removeFromInventory(player, itemCost);
        }
        profile.getUnlockedZoneIds().add(zone.id());
        packs.getPlayerStore().save(player.getUniqueId());

        Set<String> shown = wallShownFor.get(player.getUniqueId());
        if (shown != null && shown.remove(zone.id())) {
            restoreWall(player, zone);
        }
        Bukkit.getPluginManager().callEvent(new ZoneUnlockedEvent(player, zone));
        return PurchaseResult.SUCCESS;
    }

    private int countInInventory(Player player, ZoneUnlockCost.ItemCost cost) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == cost.material()) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** Mutates the live inventory slots directly (the array from {@code getContents()} holds real references) - safe since {@link #attemptPurchase} already confirmed enough exists before calling this. */
    private void removeFromInventory(Player player, ZoneUnlockCost.ItemCost cost) {
        int remaining = cost.amount();
        for (ItemStack item : player.getInventory().getContents()) {
            if (remaining <= 0) {
                break;
            }
            if (item == null || item.getType() != cost.material()) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
        }
    }

    /** See {@link #accessGates}. */
    public interface AccessGate {

        /** Null to allow entry, or the reason to show the player, already MiniMessage-formatted. */
        String denyReason(Player player, ZoneDefinition zone);
    }

    public void registerAccessGate(String key, AccessGate gate) {
        accessGates.put(key, gate);
    }

    public void unregisterAccessGate(String key) {
        accessGates.remove(key);
    }

    /**
     * Why this player cannot enter {@code zone} right now, or null if they
     * can - unlock state aside, which {@link #isUnlocked} owns.
     * <p>
     * The bypass permission is honoured here rather than only at the move
     * handler, so every way INTO a zone (walking, fast travel, anything
     * added later) gets the same answer from one place.
     */
    public String accessDenialReason(Player player, ZoneDefinition zone) {
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return null;
        }
        for (AccessGate gate : accessGates.values()) {
            String reason = gate.denyReason(player, zone);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        for (ZoneDefinition zone : zones.get().values()) {
            if (!zone.region().contains(to)) {
                continue;
            }
            if (!zone.unlockCost().isFree() && !isUnlocked(player, zone)) {
                event.setCancelled(true);
                return;
            }
            // A closed zone turns players away with a reason, because
            // unlike a locked one it has no wall to explain itself - being
            // silently unable to walk forwards reads as a broken server.
            String denial = accessDenialReason(player, zone);
            if (denial != null) {
                event.setCancelled(true);
                tellDenied(player, denial);
                return;
            }
        }
    }

    /** At most one denial message every {@value #DENIAL_MESSAGE_INTERVAL_MILLIS}ms - a blocked player walks into the boundary many times a second. */
    private void tellDenied(Player player, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastDenialAt.get(player.getUniqueId());
        if (last != null && now - last < DENIAL_MESSAGE_INTERVAL_MILLIS) {
            return;
        }
        lastDenialAt.put(player.getUniqueId(), now);
        player.sendActionBar(Text.parse(reason));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        wallShownFor.remove(id);
        lastAutoPopupAt.remove(id);
        lastDenialAt.remove(id);
    }

    /**
     * {@link Player#sendBlockChange} is silently dropped if the target chunk
     * isn't loaded on the client yet - and right at join (or right after a
     * long teleport), a wall's chunks routinely aren't loaded yet by the
     * time {@link #renderWall} first fires, since that runs on this
     * service's own tick loop rather than waiting for chunk load. The
     * server-side bookkeeping ({@code wallShownFor}) still marks it as
     * "shown" regardless, so nothing would otherwise ever retry it - this
     * re-sends whichever of a player's currently-shown walls fall inside a
     * chunk once it actually finishes loading for them, the same fix
     * {@code OreCubeService#onChunkLoad} already applies to cubes.
     */
    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        Set<String> shown = wallShownFor.get(player.getUniqueId());
        if (shown == null || shown.isEmpty()) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (String zoneId : shown) {
            ZoneDefinition zone = zones.get().get(zoneId);
            if (zone == null) {
                continue;
            }
            for (ZoneWall wall : zone.walls()) {
                ZoneRegion region = wall.region();
                if (!region.world().equals(event.getChunk().getWorld())) {
                    continue;
                }
                int minX = Math.max(region.minX(), chunkX * 16);
                int maxX = Math.min(region.maxX(), chunkX * 16 + 15);
                int minZ = Math.max(region.minZ(), chunkZ * 16);
                int maxZ = Math.min(region.maxZ(), chunkZ * 16 + 15);
                if (minX > maxX || minZ > maxZ) {
                    continue;
                }
                var blockData = wall.material().createBlockData();
                for (int x = minX; x <= maxX; x++) {
                    for (int y = region.minY(); y <= region.maxY(); y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            player.sendBlockChange(new Location(region.world(), x, y, z), blockData);
                        }
                    }
                }
            }
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(BYPASS_PERMISSION)) {
                tickPlayer(player);
            }
        }
    }

    private void tickPlayer(Player player) {
        // Once per sweep, not once per wall per zone - getLocation() hands
        // back a fresh copy every call, and this runs every 4 ticks for
        // every player against every zone.
        Location at = player.getLocation();
        for (ZoneDefinition zone : zones.get().values()) {
            if (zone.walls().isEmpty()) {
                continue;
            }
            Set<String> shown = wallShownFor.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            boolean locked = !isUnlocked(player, zone);
            boolean withinRenderRange = locked && withinRadius(at, zone, WALL_RENDER_RADIUS);

            if (withinRenderRange) {
                if (shown.add(zone.id())) {
                    renderWall(player, zone);
                }
                if (purchaseGui != null && withinRadius(at, zone, AUTO_POPUP_RADIUS)) {
                    maybeAutoPopup(player, zone);
                }
            } else if (shown.remove(zone.id())) {
                restoreWall(player, zone);
            }
        }
    }

    private void maybeAutoPopup(Player player, ZoneDefinition zone) {
        Map<String, Long> cooldowns = lastAutoPopupAt.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        if (now - cooldowns.getOrDefault(zone.id(), 0L) < AUTO_POPUP_COOLDOWN_MILLIS) {
            return;
        }
        cooldowns.put(zone.id(), now);
        purchaseGui.open(player, zone);
    }

    private boolean withinRadius(Location at, ZoneDefinition zone, double radius) {
        for (ZoneWall wall : zone.walls()) {
            if (distanceToRegion(at, wall.region()) <= radius) {
                return true;
            }
        }
        return false;
    }

    private double distanceToRegion(Location loc, ZoneRegion region) {
        if (!loc.getWorld().equals(region.world())) {
            return Double.MAX_VALUE;
        }
        double cx = clamp(loc.getX(), region.minX(), region.maxX() + 1.0);
        double cy = clamp(loc.getY(), region.minY(), region.maxY() + 1.0);
        double cz = clamp(loc.getZ(), region.minZ(), region.maxZ() + 1.0);
        double dx = loc.getX() - cx;
        double dy = loc.getY() - cy;
        double dz = loc.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void renderWall(Player player, ZoneDefinition zone) {
        for (ZoneWall wall : zone.walls()) {
            ZoneRegion region = wall.region();
            var blockData = wall.material().createBlockData();
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        Location loc = new Location(region.world(), x, y, z);
                        player.sendBlockChange(loc, blockData);
                        // FakeBlockClickRegistry's own raycast range is
                        // effectively unbounded (built for ore cubes, which
                        // should be reachable anywhere in their zone) - a
                        // wall spans many blocks and clicking it from far
                        // away felt wrong, so this is gated separately here
                        // rather than by changing that shared range.
                        FakeBlockClickRegistry.register(player, loc, clicker -> {
                            if (purchaseGui != null && clicker.getLocation().distance(loc) <= WALL_CLICK_RANGE) {
                                purchaseGui.open(clicker, zone);
                            }
                        });
                    }
                }
            }
        }
    }

    private void restoreWall(Player player, ZoneDefinition zone) {
        for (ZoneWall wall : zone.walls()) {
            ZoneRegion region = wall.region();
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        Location loc = new Location(region.world(), x, y, z);
                        player.sendBlockChange(loc, loc.getBlock().getBlockData());
                        FakeBlockClickRegistry.unregister(player, loc);
                    }
                }
            }
        }
    }
}
