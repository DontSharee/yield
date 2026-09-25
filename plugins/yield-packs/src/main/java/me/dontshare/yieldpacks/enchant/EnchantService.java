package me.dontshare.yieldpacks.enchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Player-level (not per-pet - see the plan's Decision #9) enchant slots -
 * matches PS99's actual Enchantment Slots structure exactly (see
 * https://pet-simulator.fandom.com/wiki/Enchants_(Pet_Simulator_99)#Enchantment_Slots):
 * 9 total, 6 free (unlocked progressively through in-game progression -
 * PS99 gates these by player "Rank"; Yield has no such stat inside
 * yield-packs itself, so rebirth count stands in, at thresholds shaped
 * like PS99's own 1/3/5/8/11/20 curve) + 3 premium, unlocked by external
 * "bonus slot" contributors (donor ranks - see {@link
 * #registerBonusSlotProvider}, mirroring PS99's Robux-purchased slots).
 * Slots are populated via real drag-and-drop ({@code EnchantGui}'s
 * {@code editableSlots}). Books come from two places: a very rare drop
 * when an ore cube dies (see {@link #tryDropBook}), and the hourly Enchant
 * Market (see {@code EnchantMarketService}). Hatching used to drop them
 * one egg in twenty - about 68 books an hour - which made every book
 * junk; they are meant to be found. Same-type stacking has
 * diminishing returns (see {@link #DECAY}), computed fresh from {@code
 * PackPlayerProfile.getEnchantSlots()} on every call rather than cached -
 * matches this codebase's existing "always computed from current config"
 * philosophy ({@code LuckService}/{@code PlayerLevelingService}), and the
 * per-call cost (decoding up to 9 short strings) is smaller than work
 * already accepted elsewhere on the same hot paths (e.g. {@code
 * LuckService.totalLuckMultiplier}'s own per-roll pack-collection scan).
 */
public final class EnchantService {

    public static final int SLOT_COUNT = 9;
    private static final int FREE_SLOTS = 6;
    /** Rebirth count required to unlock free slot index i (0-based) - slot 0 needs none, shaped like PS99's own Rank 1/3/5/8/11/20 curve. */
    private static final int[] FREE_SLOT_REBIRTHS = {0, 1, 3, 6, 12, 25};

    /** Diminishing-returns multiplier applied to each successive same-type book, by slot-fill order (not sorted by value) - matches the two confirmed PS99 data points (100%, 60%) continued at the same ~0.72x ratio. */
    private static final double[] DECAY = {1.0, 0.60, 0.383, 0.275, 0.21, 0.168, 0.14, 0.12, 0.09};

    /** Flat bonus magnitude per rarity tier, indexed by {@link Rarity#sortOrder()} - common(+5%) up to secret(+50%), same doubling-ish spirit as the zone pack rarity ladder. */
    private static final double[] MAGNITUDE_BY_SORT = {0.05, 0.08, 0.12, 0.18, 0.26, 0.35, 0.42, 0.50};

    /**
     * How likely a FOUND book is to land on each rarity tier, indexed by
     * {@link Rarity#sortOrder()}, before luck.
     * <p>
     * Skewed well up from the egg-pool proportions this used to share
     * (100 / 40 / 15 / 4 / 1 ...). Those were set when a book fell out of
     * one hatch in twenty - ~68 an hour - and 62% commons were fine because
     * nobody read them. A find is now a few an hour, so a common is a
     * wasted moment. With the market's own table (enchant-market.yml) and
     * the boss block's Epic floor, a full run at luck 1 comes out at ~18
     * Epics, ~6 Legendaries and ~1 Mythic - the same enchant power the old
     * drop gave, from ~100 books instead of ~775.
     */
    private static final double[] DROP_WEIGHT_BY_SORT = {40, 30, 18, 8, 3, 0.8, 0.15, 0.05};

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<RarityRegistry> rarities;
    private final EnchantItem enchantItem;

    private final Map<String, Function<PackPlayerProfile, Integer>> bonusSlotProviders = new ConcurrentHashMap<>();

    public EnchantService(PlayerDataStore<PackPlayerProfile> store, Supplier<RarityRegistry> rarities, EnchantItem enchantItem) {
        this.store = store;
        this.rarities = rarities;
        this.enchantItem = enchantItem;
    }

    public void registerBonusSlotProvider(String key, Function<PackPlayerProfile, Integer> provider) {
        bonusSlotProviders.put(key, provider);
    }

    public void unregisterBonusSlotProvider(String key) {
        bonusSlotProviders.remove(key);
    }

    /** How many of the 9 slots are currently unlocked for this profile - the rest render locked in {@code EnchantGui}. */
    public int totalSlots(PackPlayerProfile profile) {
        int unlocked = 0;
        for (int threshold : FREE_SLOT_REBIRTHS) {
            if (profile.getRebirths() >= threshold) {
                unlocked++;
            }
        }
        for (Function<PackPlayerProfile, Integer> provider : bonusSlotProviders.values()) {
            unlocked += provider.apply(profile);
        }
        return Math.min(SLOT_COUNT, unlocked);
    }

    /** Rebirth count still needed before free slot index {@code slotIndex} (0-based) unlocks - 0 if already unlocked or if it's a premium (non-free) slot. Used by {@code EnchantGui}'s locked-slot lore. */
    public int rebirthsNeededFor(PackPlayerProfile profile, int slotIndex) {
        if (slotIndex >= FREE_SLOTS) {
            return 0;
        }
        return Math.max(0, FREE_SLOT_REBIRTHS[slotIndex] - profile.getRebirths());
    }

    /** Whether slot index {@code slotIndex} (0-based) is one of the 3 premium (donor-rank-gated) slots rather than a free one - used by {@code EnchantGui}'s locked-slot lore. */
    public boolean isPremiumSlot(int slotIndex) {
        return slotIndex >= FREE_SLOTS;
    }

    /** Same-type-decayed sum of every slotted book's magnitude, per type - 0.0 for a type with nothing slotted. */
    private Map<EnchantType, Double> totalsByType(PackPlayerProfile profile) {
        Map<EnchantType, Double> totals = new EnumMap<>(EnchantType.class);
        Map<EnchantType, Integer> countByType = new EnumMap<>(EnchantType.class);
        List<String> slots = profile.getEnchantSlots();
        for (String encoded : slots) {
            if (encoded == null || encoded.isEmpty()) {
                continue;
            }
            String[] parts = encoded.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            EnchantType type;
            try {
                // "GEMS" is the pre-rename name for DIAMONDS - a book already
                // slotted under the old name must keep resolving, or an
                // existing player's slotted Enchant Book silently stops
                // counting the instant this loads. Self-healing: the next
                // time this slot is written, it's re-encoded under the
                // current enum name.
                type = parts[0].equals("GEMS") ? EnchantType.DIAMONDS : EnchantType.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Rarity rarity = rarities.get().find(parts[1]).orElse(null);
            if (rarity == null) {
                continue;
            }
            int occurrence = countByType.merge(type, 1, Integer::sum) - 1;
            double decay = occurrence < DECAY.length ? DECAY[occurrence] : DECAY[DECAY.length - 1];
            double magnitude = magnitudeFor(rarity) * decay;
            totals.merge(type, magnitude, Double::sum);
        }
        return totals;
    }

    /** A multiplier-registry-shaped function (1.0 + total) for COINS/DIAMONDS/DAMAGE/ATTACK_SPEED. */
    public Function<PackPlayerProfile, Double> multiplierFor(EnchantType type) {
        return profile -> 1.0 + totalsByType(profile).getOrDefault(type, 0.0);
    }

    /** LuckService's own registry is additive already (no +1.0) - LUCK enchants feed it directly. */
    public Function<PackPlayerProfile, Double> additiveFor(EnchantType type) {
        return profile -> totalsByType(profile).getOrDefault(type, 0.0);
    }

    /** Flat bonus magnitude for one book of this rarity, before same-type decay - shared with {@link EnchantItem} for lore display. */
    public static double magnitudeFor(Rarity rarity) {
        int i = rarity.sortOrder();
        return i >= 0 && i < MAGNITUDE_BY_SORT.length ? MAGNITUDE_BY_SORT[i] : MAGNITUDE_BY_SORT[MAGNITUDE_BY_SORT.length - 1];
    }

    /**
     * Rolls a cube's Enchant Book drop: {@code chance} (the cube's own -
     * see yield-zones' CubeTier#bookChance), scaled by luck, and on a hit a
     * random type at a luck-weighted rarity. Announced, because a drop this
     * rare that lands silently in an inventory is one nobody notices.
     * Returns whether a book dropped.
     */
    public boolean tryDropBook(Player player, double chance, double luckMultiplier) {
        return tryDropBook(player, chance, luckMultiplier, null);
    }

    /**
     * {@link #tryDropBook(Player, double, double)} that never lands below
     * {@code minRarityId} (null for no floor) - the boss block's book is
     * its prize, so it is always at least Epic. The roll is the normal
     * weighted one restricted to the floor and above, so the tiers keep
     * their proportions to each other.
     */
    public boolean tryDropBook(Player player, double chance, double luckMultiplier, String minRarityId) {
        return rollBookDrop(player, chance, luckMultiplier, minRarityId) != null;
    }

    /** {@link #tryDropBook(Player, double, double, String)}, returning the rarity found (null for none) - so the caller can stage the moment in that rarity's colour. */
    public Rarity rollBookDrop(Player player, double chance, double luckMultiplier, String minRarityId) {
        if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= Math.min(1.0, chance * luckMultiplier)) {
            return null;
        }
        int minSort = minRarityId == null ? 0 : rarities.get().find(minRarityId).map(Rarity::sortOrder).orElse(0);
        EnchantType[] types = EnchantType.values();
        EnchantType type = types[ThreadLocalRandom.current().nextInt(types.length)];
        Rarity rarity = rollRarity(luckMultiplier, minSort);
        if (rarity == null) {
            return null;
        }
        giveItem(player, enchantItem.create(type, rarity));
        announceFind(player, type, rarity);
        return rarity;
    }

    /**
     * Chat line and a chime for any book; a full title for Legendary and up,
     * which at these odds is a once-in-many-hours moment.
     */
    private void announceFind(Player player, EnchantType type, Rarity rarity) {
        String accent = "<" + rarity.colorHex() + ">";
        String name = accent + "<bold>" + rarity.displayName().toUpperCase(Locale.ROOT) + "</bold></"
                + rarity.colorHex() + "> <white>" + type.displayName() + " Enchant</white>";
        player.sendMessage(Text.parse("<#4BD9FF>✦</#4BD9FF> <gray>You found a</gray> " + name
                + " <gray>book!</gray>"));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        if (rarity.sortOrder() >= LEGENDARY_SORT) {
            player.showTitle(Title.title(Text.parse(accent + "<bold>" + rarity.displayName().toUpperCase(Locale.ROOT)
                            + " BOOK!</bold>"),
                    Text.parse("<white>" + type.displayName() + " Enchant</white>")));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
    }

    /** {@code Rarity#sortOrder} of Legendary - the line past which a book is worth a title card and a market ping. */
    public static final int LEGENDARY_SORT = 4;

    /** Direct grant - "/admin packs give" and the Enchant Market both hand books over through here. */
    public void giveBook(Player player, EnchantType type, Rarity rarity) {
        giveItem(player, enchantItem.create(type, rarity));
    }

    private Rarity rollRarity(double luckMultiplier, int minSort) {
        List<Rarity> ordered = new ArrayList<>(rarities.get().all());
        ordered.removeIf(rarity -> rarity.sortOrder() < minSort);
        ordered.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        double[] adjusted = new double[ordered.size()];
        double total = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Rarity rarity = ordered.get(i);
            int idx = rarity.sortOrder();
            double baseWeight = idx >= 0 && idx < DROP_WEIGHT_BY_SORT.length ? DROP_WEIGHT_BY_SORT[idx] : DROP_WEIGHT_BY_SORT[DROP_WEIGHT_BY_SORT.length - 1];
            double w = baseWeight * Math.pow(luckMultiplier, rarity.luckExponent());
            adjusted[i] = w;
            total += w;
        }
        if (total <= 0) {
            return ordered.isEmpty() ? null : ordered.get(0);
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < ordered.size(); i++) {
            cumulative += adjusted[i];
            if (roll < cumulative) {
                return ordered.get(i);
            }
        }
        return ordered.get(ordered.size() - 1);
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
