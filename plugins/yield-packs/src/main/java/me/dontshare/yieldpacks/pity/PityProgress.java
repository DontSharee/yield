package me.dontshare.yieldpacks.pity;

/** Progress toward the next pity milestone, for the action bar's progress bar. */
public record PityProgress(int current, int max, double nextMultiplier) {
}
