package me.dontshare.yieldspawnnpcs.crate;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.List;

/**
 * One crate tier - not bought with any currency; {@code keyDropChance} is
 * this crate's own per-ore-cube-kill chance to find one of its Key (see
 * {@code CrateKeyDropListener}), and {@code location} + the 4 entity ids
 * are its one global, always-visible physical station (see
 * {@code CrateDisplay}) - smacking it spends 1 Key and rolls {@code pool}.
 */
public record CrateDefinition(String id, String displayName, Material icon, double keyDropChance,
                               Location location, int hitboxEntityId, int buttonEntityId,
                               int wallEntityId, int textEntityId, List<CrateRewardEntry> pool) {
}
