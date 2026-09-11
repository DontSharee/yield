package me.dontshare.yieldpacks.roll;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players currently have a {@link PackRevealAnimationService} reel
 * playing - {@code PackActionBarService}'s own once-a-second action-bar loop
 * checks this and skips anyone active here, since both would otherwise
 * overwrite each other's action-bar text every tick. Static/global by
 * design, same idiom as {@code FakeBlockClickRegistry}'s own static
 * registries in yield-core - there's exactly one reel per player at a time,
 * never plugin-instance-scoped state.
 */
public final class RevealSuppressionRegistry {

    private static final Set<UUID> active = ConcurrentHashMap.newKeySet();

    private RevealSuppressionRegistry() {
    }

    public static void begin(UUID playerId) {
        active.add(playerId);
    }

    public static void end(UUID playerId) {
        active.remove(playerId);
    }

    public static boolean isActive(UUID playerId) {
        return active.contains(playerId);
    }
}
