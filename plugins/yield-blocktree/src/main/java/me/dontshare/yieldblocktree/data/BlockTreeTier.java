package me.dontshare.yieldblocktree.data;

import java.util.List;

/** One rung of a block's tree - {@code goal} is the cumulative lifetime break count required; {@code effects} are every reward granted (permanently) once claimed. */
public record BlockTreeTier(long goal, List<BlockTreeEffect> effects) {
}
