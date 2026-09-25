package me.dontshare.yieldcore.status;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.perf.PerfTracker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;

/**
 * One reading of how the server is doing - the numbers {@code /yield status}
 * prints and the analytics site charts, taken the same way for both.
 */
public record ServerHealth(
        double tps1m, double tps5m, double tps15m,
        double msptAvg, double msptP95, double msptMax,
        long heapUsedMb, long heapMaxMb,
        int online, int dbQueued, int dbActive,
        int cachedRecords, int writesInFlight,
        double packetsPerSecond, double trackedMsPerTick,
        List<PlayerDataStore.StoreStats> stores) {

    /** Safe off the main thread: everything read here is a plain field or a copy. */
    public static ServerHealth read() {
        double[] tps = Bukkit.getTPS();
        long[] tickTimes = Bukkit.getServer().getTickTimes().clone();
        long[] sorted = Arrays.stream(tickTimes).filter(t -> t > 0).sorted().toArray();
        double p95 = sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))] / 1e6;
        double max = sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6;
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long heapMax = runtime.maxMemory() / (1024 * 1024);
        var db = JavaPlugin.getPlugin(YieldCore.class).getDatabaseManager();
        List<PlayerDataStore.StoreStats> stores = PlayerDataStore.allStats();
        int cached = 0;
        int inFlight = 0;
        for (PlayerDataStore.StoreStats store : stores) {
            cached += store.cached();
            inFlight += store.writesInFlight();
        }
        return new ServerHealth(
                Math.min(20, tps[0]), Math.min(20, tps[1]), Math.min(20, tps[2]),
                Bukkit.getAverageTickTime(), p95, max,
                used, heapMax,
                Bukkit.getOnlinePlayers().size(), db.queuedTasks(), db.activeTasks(),
                cached, inFlight,
                PerfTracker.packetsPerSecond(), PerfTracker.trackedMsPerTick(),
                stores);
    }
}
