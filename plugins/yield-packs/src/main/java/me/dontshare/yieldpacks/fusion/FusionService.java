package me.dontshare.yieldpacks.fusion;

import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Combines 3 copies of one fusion tier into 1 of the next - see {@link FusionTier}.
 * Only ever touches baseline instances ({@link PetInstance#isBaseline()},
 * and never anything currently equipped) - a pet with real level/XP
 * progress can never be fused away, on purpose: duplicates worth keeping
 * should get leveled up individually instead.
 */
public final class FusionService {

    private static final int COST = 3;
    /** Caps a single {@link #fuseAll} call at this many individual fuses - shared by FusionGui's "Fuse All" button and AutoFuseService's per-tick sweep, keeping a huge backlog from doing unbounded work in one synchronous pass. */
    public static final int FUSE_ALL_CAP = 100;

    private final Supplier<ItemRegistry> itemRegistry;

    public FusionService(Supplier<ItemRegistry> itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    /** Baseline, unequipped copies of this exact id - only these are fusable. */
    public int remainingCount(PackPlayerProfile profile, String itemId) {
        return (int) fusableInstances(profile, itemId).count();
    }

    public boolean canFuse(PackPlayerProfile profile, String itemId) {
        ItemDefinition item = itemRegistry.get().find(itemId).orElse(null);
        return item != null && item.fusionTier().next() != null && remainingCount(profile, itemId) >= COST;
    }

    /** Consumes 3 baseline storage copies of {@code itemId} and grants 1 fresh baseline instance of the next tier - returns the new item's id, or null if {@link #canFuse} would be false. */
    public String fuse(PackPlayerProfile profile, String itemId) {
        if (!canFuse(profile, itemId)) {
            return null;
        }
        ItemDefinition item = itemRegistry.get().getOrThrow(itemId);
        List<PetInstance> consumed = fusableInstances(profile, itemId).limit(COST).toList();
        profile.removePets(consumed);

        String nextId = item.fusionTier().next().idFor(item.baseItemId());
        profile.addPet(new PetInstance(UUID.randomUUID(), nextId));
        return nextId;
    }

    /**
     * Repeatedly fuses everything currently fusable, cascading through
     * tiers in one call (e.g. 9 Golden -> 3 Rainbow -> 1 Dark Matter),
     * until nothing more can be fused or {@code cap} individual fuses have
     * happened - bounds worst-case work to roughly {@code cap *
     * distinct-owned-items}, trivially fast even for a large bag. Returns
     * every result id produced, in order, so the caller can fire one
     * {@code PetFusedEvent} per entry (keeping quest tracking correct
     * without any special-casing there).
     */
    public List<String> fuseAll(PackPlayerProfile profile, int cap) {
        return cascade(profile, cap, null);
    }

    /**
     * The shared sweep behind {@link #fuseAll} and {@link #fuseAllToTier}.
     * <p>
     * Driven off a single count of what's fusable rather than re-deriving it
     * per candidate. Asking {@code canFuse} about each distinct owned item
     * meant a fresh pass over the whole bag per question, so simply
     * establishing that there was nothing to do - the normal outcome when
     * auto-fuse runs on a timer - cost the bag size times the number of
     * distinct items owned, every time. Counting once and then decrementing
     * as we go makes an idle sweep a single pass.
     *
     * @param targetTier when set, only fuses whose result is exactly this tier are performed
     */
    private List<String> cascade(PackPlayerProfile profile, int cap, FusionTier targetTier) {
        Map<String, Integer> fusable = fusableCounts(profile);
        List<String> results = new ArrayList<>();
        boolean progressed = true;
        while (results.size() < cap && progressed) {
            progressed = false;
            for (String itemId : List.copyOf(fusable.keySet())) {
                if (results.size() >= cap) {
                    break;
                }
                ItemDefinition item = itemRegistry.get().find(itemId).orElse(null);
                if (item == null || item.fusionTier().next() == null) {
                    continue;
                }
                if (targetTier != null && item.fusionTier().next() != targetTier) {
                    continue;
                }
                while (results.size() < cap && fusable.getOrDefault(itemId, 0) >= COST) {
                    String nextId = fuseCounted(profile, item, itemId);
                    if (nextId == null) {
                        break;
                    }
                    fusable.merge(itemId, -COST, Integer::sum);
                    fusable.merge(nextId, 1, Integer::sum);
                    results.add(nextId);
                    progressed = true;
                }
            }
        }
        return results;
    }

    /** {@link #fuse} without re-deriving whether it's allowed - the caller's count already established that. */
    private String fuseCounted(PackPlayerProfile profile, ItemDefinition item, String itemId) {
        List<PetInstance> consumed = fusableInstances(profile, itemId).limit(COST).toList();
        if (consumed.size() < COST) {
            return null;
        }
        profile.removePets(consumed);
        String nextId = item.fusionTier().next().idFor(item.baseItemId());
        profile.addPet(new PetInstance(UUID.randomUUID(), nextId));
        return nextId;
    }

    /** How many baseline, unequipped copies of each owned item id there are - one pass over the bag. */
    private Map<String, Integer> fusableCounts(PackPlayerProfile profile) {
        Set<UUID> equipped = new HashSet<>(profile.getEquippedPetIds());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PetInstance pet : profile.getPets()) {
            if (pet.isBaseline() && !equipped.contains(pet.getInstanceId())) {
                counts.merge(pet.getItemId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Same repeated-sweep shape as {@link #fuseAll}, but only ever fuses
     * items whose next tier is EXACTLY {@code targetTier} - used by a
     * tier-locked machine (e.g. the Golden Pet Machine only ever produces
     * Golden, never cascading on into Rainbow/Dark Matter the way the
     * unrestricted {@link #fuseAll} deliberately does). Since a fuse's
     * OUTPUT is always {@code targetTier} itself, and {@code targetTier}
     * can never also be its own next tier, this can never cascade past one
     * real pass over each distinct owned item - the repeat loop only
     * matters for consuming several batches of 3 of the SAME base item.
     */
    public List<String> fuseAllToTier(PackPlayerProfile profile, FusionTier targetTier, int cap) {
        return cascade(profile, cap, targetTier);
    }

    private java.util.stream.Stream<PetInstance> fusableInstances(PackPlayerProfile profile, String itemId) {
        return profile.getPets().stream()
                .filter(pet -> pet.getItemId().equals(itemId) && pet.isBaseline() && !profile.isEquipped(pet.getInstanceId()));
    }

}
