package me.dontshare.yieldpacks.pet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One specific, permanent pet a player owns - equipped or sitting in the
 * Bag, it's the same object either way and never loses its progress.
 * Stores only progress ({@code level}/{@code xp}/{@code bonusLevelCap}),
 * never a computed stat - effective damage/earnings are always derived
 * fresh from current config (see yield-packs' PetLevelingService) so a
 * balance change picks up every already-leveled pet instantly. Deliberately
 * mutable (not a record) - needs a public no-arg constructor and plain
 * getters/setters for the MongoDB POJO codec, same convention as
 * {@code PackPlayerProfile}.
 */
public final class PetInstance {

    private UUID instanceId;
    private String itemId;
    private int level = 1;
    private long xp;
    private int bonusLevelCap;
    // Permanent, per-PET bonuses from forged held items (see yield-mining's
    // ForgeStatType/ForgeBoostService) - keyed by ForgeStatType#name().
    // Deliberately lives on the pet, not the player: a DAMAGE bonus only
    // ever applies to THIS pet's own effectiveDamage (see EquipmentService),
    // and a COINS/DIAMONDS/LUCK/ATTACK_SPEED bonus - stats this codebase has no
    // other per-pet concept of - only counts toward the player's global
    // multiplier while this specific pet is equipped, so which pets you
    // choose to feed items to (and keep equipped) actually matters.
    private Map<String, Double> forgeBonuses = new HashMap<>();
    // Permanent, per-PET bonuses from the Enchanting Table (see
    // me.dontshare.yieldpacks.petenchant) - a wholly separate mechanic
    // from forgeBonuses above (rolled, not fed; replaced wholesale on
    // every re-enchant, never accumulated) but the same "lives on the
    // pet, DAMAGE affects only this pet, the rest count toward the
    // player's global multiplier only while equipped" reasoning.
    // enchantBonuses is keyed by PetEnchantType#name(); activeUniqueEnchants
    // holds 0-2 PetUniqueDefinition ids (a Unique roll can also carry its
    // own stat bonuses, already folded into enchantBonuses - this list is
    // only for lore display and special-effect hooks like Glittering).
    private Map<String, Double> enchantBonuses = new HashMap<>();
    private List<String> activeUniqueEnchants = new ArrayList<>();
    // Rolled once, when this pet is obtained, and never changes afterwards
    // (see PackRollService). Lives here rather than as its own
    // ItemDefinition because Shiny stacks with everything else a pet can be
    // - rarity, fusion tier, Huge - and synthesizing a definition per
    // combination would multiply the registry for no gain. See
    // VariantConfig for the full reasoning.
    private boolean shiny;

    public PetInstance() {
    }

    public PetInstance(UUID instanceId, String itemId) {
        this.instanceId = instanceId;
        this.itemId = itemId;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(UUID instanceId) {
        this.instanceId = instanceId;
    }

    /** The base pet or fusion-tier id this instance is a copy of, e.g. "stray_cat" / "stray_cat_golden". */
    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /** Whether this specific pet rolled Shiny when it was obtained - a permanent, purely-per-instance property. */
    public boolean isShiny() {
        return shiny;
    }

    public void setShiny(boolean shiny) {
        this.shiny = shiny;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getXp() {
        return xp;
    }

    public void setXp(long xp) {
        this.xp = xp;
    }

    /** Extra level cap raised by feeding candy, on top of the item's own default cap - see PetLevelingService. */
    public int getBonusLevelCap() {
        return bonusLevelCap;
    }

    public void setBonusLevelCap(int bonusLevelCap) {
        this.bonusLevelCap = bonusLevelCap;
    }

    public Map<String, Double> getForgeBonuses() {
        return forgeBonuses;
    }

    public Map<String, Double> getEnchantBonuses() {
        return enchantBonuses;
    }

    public List<String> getActiveUniqueEnchants() {
        return activeUniqueEnchants;
    }

    /**
     * Still at baseline - genuinely interchangeable with any other baseline
     * copy of the same item id, so the Bag may stack them into one slot and
     * fusion/bulk-delete may consume them without asking which one.
     * <p>
     * "Interchangeable" has to mean EVERY permanent thing that can make one
     * copy different from another, not just its level:
     * <ul>
     *   <li>level/xp/bonusLevelCap - fought with, or fed candy</li>
     *   <li>{@link #isShiny()} - a Shiny is a different pet to its owner and
     *       hits harder; stacking one in with plain copies would show the
     *       wrong badge and could feed it to a fusion by accident</li>
     *   <li>forge and enchant bonuses - both can be applied to a bagged,
     *       never-levelled pet, so a level-1 pet can still be carrying
     *       permanent, irreplaceable stats</li>
     * </ul>
     * The last two were missing, and the consequence was not cosmetic:
     * bulk-delete-by-rarity and fusion both consume baseline pets, so an
     * enchanted or forged level-1 pet could be destroyed without warning.
     */
    public boolean isBaseline() {
        return level <= 1
                && xp == 0
                && bonusLevelCap == 0
                && !shiny
                && forgeBonuses.isEmpty()
                && enchantBonuses.isEmpty()
                && activeUniqueEnchants.isEmpty();
    }
}
