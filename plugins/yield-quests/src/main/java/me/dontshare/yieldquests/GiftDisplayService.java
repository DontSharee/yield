package me.dontshare.yieldquests;

import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.packet.InteractionEntityManager;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldquests.data.PresentDefinition;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.cube.LootDropService;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The free gifts as real presents: when one unlocks it drops out of the sky
 * next to the player, spinning and glowing under a "FREE GIFT" label, and
 * waits for a smack. Opening it shakes it, pops it in a burst of light, and
 * sprays what it pays as coin/diamond loot that flies to the player (see
 * {@link LootDropService}), credited as it lands.
 * <p>
 * One present on the ground at a time; the next unlocked one follows a
 * moment after the last is opened. Walk off and it hops back beside you.
 * Everything is per-player packets, particles and sounds - nobody else sees
 * or pays for anyone's gifts.
 */
public final class GiftDisplayService implements Listener {

    private static final long TICK_INTERVAL = 10L;
    private static final double DROP_HEIGHT = 7.0;
    private static final int FALL_TICKS = 9;
    /** Further than this from its owner and the present hops back beside them. */
    private static final double FOLLOW_RANGE = 10.0;
    /** Between one present opening and the next unlocked one dropping. */
    private static final long NEXT_GIFT_DELAY_MILLIS = 2500L;
    private static final float GIFT_SCALE = 0.95f;
    private static final int GIFT_GLOW = 0xFFC83D;
    private static final int COIN_PIECES = 24;
    private static final int DIAMOND_PIECES = 10;

    private static final class Gift {
        final int index;
        final int itemId = PacketEntityManager.nextEntityId();
        final int textId = PacketEntityManager.nextEntityId();
        final int hitboxId = PacketEntityManager.nextEntityId();
        Location landing;
        float yaw;
        boolean opening;
        /** Dropped from the /daily menu: opens as soon as it lands. */
        final boolean openOnLanding;
        /** What it pays - fixed up front for a streak present, set when claimed for a free gift. */
        PresentsService.Reward reward;
        /** Its reward has gone out, as loot or straight to the balance - see {@link #onQuit}. */
        boolean paid;
        /** A streak present rather than one of the session's free gifts - its reward is already fixed. */
        String title = "✦ FREE GIFT ✦";
        ItemStack icon;

        Gift(int index, boolean openOnLanding) {
            this.index = index;
            this.openOnLanding = openOnLanding;
        }
    }

    /** A login-streak day waiting to drop in: it goes before any free gift. */
    private record PendingStreak(int streak, boolean bigDay, PresentsService.Reward reward, long readyAtMillis) {
    }
    private final Map<UUID, PendingStreak> pendingStreaks = new ConcurrentHashMap<>();
    /** How long after joining the streak present drops - lets the join screen settle. */
    private static final long STREAK_DROP_DELAY_MILLIS = 3000L;

    private final JavaPlugin plugin;
    private final PresentsService presents;
    private final YieldPacks packs;
    private final YieldZones zones;
    private final Map<UUID, Gift> gifts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextGiftAt = new ConcurrentHashMap<>();

    public GiftDisplayService(JavaPlugin plugin, PresentsService presents, YieldPacks packs, YieldZones zones) {
        this.plugin = plugin;
        this.presents = presents;
        this.packs = packs;
        this.zones = zones;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Gift gift = gifts.get(player.getUniqueId());
            if (gift == null) {
                if (now < nextGiftAt.getOrDefault(player.getUniqueId(), 0L)) {
                    continue;
                }
                PendingStreak streak = pendingStreaks.get(player.getUniqueId());
                if (streak != null) {
                    if (now >= streak.readyAtMillis()) {
                        pendingStreaks.remove(player.getUniqueId());
                        dropStreak(player, streak);
                    }
                    continue;
                }
                int index = presents.nextOpenable(player);
                if (index >= 0) {
                    drop(player, index, false);
                    player.sendMessage(Text.parse("<#FFC83D><bold>✦ FREE GIFT!</bold></#FFC83D> <gray>A present landed next to you - smack it to open!</gray>"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.6f);
                }
                continue;
            }
            if (gift.opening || gift.landing == null) {
                continue;
            }
            if (!player.getWorld().equals(gift.landing.getWorld())
                    || player.getLocation().distanceSquared(gift.landing) > FOLLOW_RANGE * FOLLOW_RANGE) {
                // Walked off - it comes with them rather than being left behind.
                despawn(player, gift);
                gifts.remove(player.getUniqueId());
                if (gift.reward != null) {
                    // A streak present - its reward travels with it.
                    Gift again = new Gift(gift.index, false);
                    again.reward = gift.reward;
                    again.title = gift.title;
                    again.icon = gift.icon;
                    place(player, again);
                } else {
                    drop(player, gift.index, false);
                }
                continue;
            }
            // A slow, continuous turn: each step is interpolated over the
            // whole interval, so it reads as one smooth spin.
            gift.yaw = (gift.yaw + 40f) % 360f;
            ItemDisplayManager.setRotationInterpolated(player, gift.itemId, 0f, gift.yaw, (int) TICK_INTERVAL);
        }
    }

    /**
     * Opens present {@code index} from the /daily menu: it drops right in
     * front of the player and bursts as it lands - or, if that present is
     * already sitting on the ground, that one opens where it is.
     */
    public void openFromMenu(Player player, int index) {
        Gift current = gifts.get(player.getUniqueId());
        if (current != null && current.index == index) {
            open(player);
            return;
        }
        if (current != null) {
            despawn(player, current);
            gifts.remove(player.getUniqueId());
        }
        drop(player, index, true);
    }

    private void drop(Player player, int index, boolean openOnLanding) {
        List<PresentDefinition> list = presents.presents();
        if (index < 0 || index >= list.size()) {
            return;
        }
        PresentDefinition present = list.get(index);
        Gift gift = new Gift(index, openOnLanding);
        gift.icon = packs.getIconFactory().headOrFallback(present.headDatabaseId(), present.fallbackMaterial());
        place(player, gift);
    }

    /**
     * Queues today's login-streak reward as a present that drops beside the
     * player a few seconds after they join, ahead of any free gift. Its
     * coins and diamonds are fixed now and paid when it's opened - or
     * straight to the balance if they leave first (see {@link #onQuit}).
     */
    public void queueStreakGift(Player player, int streak, boolean bigDay, long coins, long diamonds) {
        pendingStreaks.put(player.getUniqueId(), new PendingStreak(streak, bigDay,
                new PresentsService.Reward(coins, diamonds), System.currentTimeMillis() + STREAK_DROP_DELAY_MILLIS));
    }

    private void dropStreak(Player player, PendingStreak streak) {
        Gift gift = new Gift(-1, false);
        gift.reward = streak.reward();
        gift.title = "✦ DAY " + streak.streak() + " STREAK ✦";
        gift.icon = new ItemStack(streak.bigDay() ? org.bukkit.Material.ENDER_CHEST : org.bukkit.Material.CHEST);
        place(player, gift);
        player.sendMessage(Text.parse("<#FFC83D><bold>✦ DAY " + streak.streak() + " STREAK!</bold></#FFC83D> <gray>Your streak present landed next to you - smack it to open!</gray>"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.3f);
    }

    /** Drops {@code gift} out of the sky beside the player. */
    private void place(Player player, Gift gift) {
        gifts.put(player.getUniqueId(), gift);

        Location feet = player.getLocation();
        Vector forward = feet.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-6) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).multiply(ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
        Location landing = feet.clone().add(forward.multiply(2.4)).add(side);
        landing.setY(feet.getY() + GIFT_SCALE / 2.0);
        landing.setYaw(0f);
        landing.setPitch(0f);
        gift.landing = landing;
        Location sky = landing.clone().add(0, DROP_HEIGHT, 0);
        ItemStack item = gift.icon;

        PacketEntityManager.beginBundle(player);
        ItemDisplayManager.spawn(player, gift.itemId, sky);
        ItemDisplayManager.setItem(player, gift.itemId, item);
        ItemDisplayManager.setScale(player, gift.itemId, GIFT_SCALE, GIFT_SCALE, GIFT_SCALE);
        ItemDisplayManager.setGlowing(player, gift.itemId, true);
        ItemDisplayManager.setGlowColor(player, gift.itemId, GIFT_GLOW);
        ItemDisplayManager.setPositionInterpolation(player, gift.itemId, FALL_TICKS);

        TextDisplayManager.spawn(player, gift.textId, labelLocation(sky));
        TextDisplayManager.setBillboard(player, gift.textId, TextDisplayManager.Billboard.CENTER);
        TextDisplayManager.setBackgroundColor(player, gift.textId, 0x00000000);
        TextDisplayManager.setStyle(player, gift.textId, true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(player, gift.textId, Text.parse(
                "<#FFC83D><bold>" + gift.title + "</bold></#FFC83D>\n<yellow><bold>CLICK TO OPEN</bold></yellow>"));
        TextDisplayManager.setPositionInterpolation(player, gift.textId, FALL_TICKS);

        // Interaction boxes stand on their bottom face.
        InteractionEntityManager.spawn(player, gift.hitboxId, landing.clone().subtract(0, GIFT_SCALE / 2.0, 0));
        InteractionEntityManager.setSize(player, gift.hitboxId, 1.1f, 1.2f);
        PacketEntityManager.endBundle(player);
        EntityClickRegistry.register(gift.hitboxId, clicker -> open(clicker));
        EntityClickRegistry.registerInteract(gift.hitboxId, clicker -> open(clicker));

        // Next tick: fall to the landing spot, gliding over FALL_TICKS.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gifts.get(player.getUniqueId()) != gift || !player.isOnline()) {
                return;
            }
            PacketEntityManager.teleportEntity(player, gift.itemId, landing);
            PacketEntityManager.teleportEntity(player, gift.textId, labelLocation(landing));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gifts.get(player.getUniqueId()) != gift || !player.isOnline()) {
                return;
            }
            player.spawnParticle(Particle.CLOUD, landing.clone().subtract(0, GIFT_SCALE / 2.0, 0), 12, 0.35, 0.05, 0.35, 0.02);
            player.playSound(landing, Sound.BLOCK_WOOL_PLACE, 0.8f, 0.8f);
            if (gift.openOnLanding) {
                open(player);
            }
        }, FALL_TICKS + 1L);
    }

    private static Location labelLocation(Location gift) {
        return gift.clone().add(0, GIFT_SCALE / 2.0 + 0.35, 0);
    }

    /** Shake, swell, pop - then the goodies fly out. */
    private void open(Player player) {
        Gift gift = gifts.get(player.getUniqueId());
        if (gift == null || gift.opening) {
            return;
        }
        PresentsService.Reward reward = gift.reward != null ? gift.reward : presents.claimForDrop(player, gift.index);
        gift.reward = reward;
        if (reward == null) {
            // Claimed elsewhere, or not actually unlocked - just clear it away.
            despawn(player, gift);
            gifts.remove(player.getUniqueId());
            return;
        }
        gift.opening = true;
        EntityClickRegistry.unregister(gift.hitboxId);
        EntityClickRegistry.unregisterInteract(gift.hitboxId);
        PacketEntityManager.destroyEntity(player, gift.textId);
        PacketEntityManager.destroyEntity(player, gift.hitboxId);
        player.playSound(gift.landing, Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.6f);

        // Swell and shrink twice, faster each time, tilting side to side.
        float[] scales = {1.25f, 0.9f, 1.4f, 1.0f, 1.6f};
        float[] tilts = {18f, -18f, 22f, -22f, 0f};
        for (int step = 0; step < scales.length; step++) {
            int s = step;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                float scale = GIFT_SCALE * scales[s];
                ItemDisplayManager.setInterpolation(player, gift.itemId, 0, 3, 0);
                ItemDisplayManager.setScale(player, gift.itemId, scale, scale, scale);
                ItemDisplayManager.setRotationInterpolated(player, gift.itemId, tilts[s], gift.yaw, 3);
                player.playSound(gift.landing, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + s * 0.2f);
            }, 1L + step * 3L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> burst(player, gift, reward), 2L + scales.length * 3L);
    }

    private void burst(Player player, Gift gift, PresentsService.Reward reward) {
        PacketEntityManager.destroyEntity(player, gift.itemId);
        gifts.remove(player.getUniqueId(), gift);
        nextGiftAt.put(player.getUniqueId(), System.currentTimeMillis() + NEXT_GIFT_DELAY_MILLIS);
        if (!player.isOnline() || gift.paid) {
            // Left mid-burst: onQuit has already paid it.
            return;
        }
        gift.paid = true;
        Location at = gift.landing;
        player.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 50, 0.3, 0.4, 0.3, 0.5);
        player.spawnParticle(Particle.FIREWORK, at, 25, 0.2, 0.2, 0.2, 0.15);
        player.spawnParticle(Particle.DUST, at, 30, 0.6, 0.6, 0.6, 0,
                new Particle.DustOptions(Color.fromRGB(GIFT_GLOW), 1.6f));
        player.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8f, 1.1f);
        player.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);

        double floorY = at.getY() - GIFT_SCALE / 2.0;
        LootDropService drops = zones.getCubeService().getLootDrops();
        drops.spawn(player, at, floorY, 0.4, LootDropService.Kind.COIN, reward.coins(), COIN_PIECES);
        drops.spawn(player, at, floorY, 0.4, LootDropService.Kind.DIAMOND, reward.diamonds(), DIAMOND_PIECES);
    }

    private void despawn(Player player, Gift gift) {
        EntityClickRegistry.unregister(gift.hitboxId);
        EntityClickRegistry.unregisterInteract(gift.hitboxId);
        PacketEntityManager.destroyEntity(player, gift.itemId);
        PacketEntityManager.destroyEntity(player, gift.textId);
        PacketEntityManager.destroyEntity(player, gift.hitboxId);
    }

    /**
     * Pays anything owed that hasn't gone out yet: a streak present still
     * waiting to drop or sitting unopened, or any present caught mid-burst.
     * (A free gift nobody opened pays nothing - the session chain re-locks
     * on logout by design.) LOWEST, so it lands before the player stores'
     * own quit save.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Gift gift = gifts.remove(id);
        if (gift != null) {
            EntityClickRegistry.unregister(gift.hitboxId);
            EntityClickRegistry.unregisterInteract(gift.hitboxId);
            if (gift.reward != null && !gift.paid) {
                gift.paid = true;
                creditDirectly(player, gift.reward);
            }
        }
        PendingStreak streak = pendingStreaks.remove(id);
        if (streak != null) {
            creditDirectly(player, streak.reward());
        }
        nextGiftAt.remove(id);
    }

    private void creditDirectly(Player player, PresentsService.Reward reward) {
        var profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        profile.setCoins(profile.getCoins().add(java.math.BigInteger.valueOf(reward.coins())));
        profile.setDiamonds(profile.getDiamonds().add(java.math.BigInteger.valueOf(reward.diamonds())));
    }
}
