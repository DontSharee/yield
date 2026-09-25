package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldcore.text.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects one number per player and summarizes it: average, median, 90th
 * percentile, max, total, and a histogram for the chart. Values spanning
 * many orders of magnitude (coins, diamonds) get log-scale buckets - one per
 * power of ten - so the chart isn't one tall bar and a flat line.
 */
final class Distribution {

    private final String key;
    private final String label;
    private final String unit;
    private final boolean logScale;
    private final List<Double> values = new ArrayList<>();

    /** The unit for values in minutes, shown as "3h 20m" rather than a number. */
    static final String DURATION = "duration";

    Distribution(String key, String label, String unit, boolean logScale) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.logScale = logScale;
    }

    void add(double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            values.add(value);
        }
    }

    Map<String, Object> summary() {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("label", label);
        out.put("unit", unit);
        out.put("count", sorted.size());
        double total = 0;
        for (double value : sorted) {
            total += value;
        }
        out.put("total", total);
        out.put("avg", sorted.isEmpty() ? 0 : total / sorted.size());
        out.put("median", percentile(sorted, 0.5));
        out.put("p90", percentile(sorted, 0.9));
        out.put("max", sorted.isEmpty() ? 0 : sorted.getLast());
        out.put("zeroShare", sorted.isEmpty() ? 0 : sorted.stream().filter(v -> v == 0).count() / (double) sorted.size());
        out.put("histogram", logScale ? logHistogram(sorted) : linearHistogram(sorted));
        return out;
    }

    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        double index = p * (sorted.size() - 1);
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    private static List<Map<String, Object>> logHistogram(List<Double> sorted) {
        Map<Integer, Integer> byExponent = new java.util.TreeMap<>();
        int zeros = 0;
        for (double value : sorted) {
            if (value < 1) {
                zeros++;
            } else {
                byExponent.merge((int) Math.floor(Math.log10(value)), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> bins = new ArrayList<>();
        if (zeros > 0) {
            bins.add(bin("0", zeros));
        }
        if (!byExponent.isEmpty()) {
            int first = ((java.util.TreeMap<Integer, Integer>) byExponent).firstKey();
            int last = ((java.util.TreeMap<Integer, Integer>) byExponent).lastKey();
            for (int exponent = first; exponent <= last; exponent++) {
                double low = Math.pow(10, exponent);
                String name = Formatting.format(low) + "–" + Formatting.format(Math.pow(10, exponent + 1));
                bins.add(bin(name, byExponent.getOrDefault(exponent, 0)));
            }
        }
        return bins;
    }

    private List<Map<String, Object>> linearHistogram(List<Double> sorted) {
        List<Map<String, Object>> bins = new ArrayList<>();
        if (sorted.isEmpty()) {
            return bins;
        }
        double min = sorted.getFirst();
        double max = sorted.getLast();
        if (max - min < 1e-9) {
            bins.add(bin(label(min, 1), sorted.size()));
            return bins;
        }
        // Whole-number data with a small range gets one bar per value.
        boolean integers = sorted.stream().allMatch(v -> v == Math.rint(v));
        if (integers && max - min <= 20 && !DURATION.equals(unit)) {
            for (long value = (long) min; value <= (long) max; value++) {
                long target = value;
                bins.add(bin(String.valueOf(value), (int) sorted.stream().filter(v -> v == target).count()));
            }
            return bins;
        }
        int count = 12;
        double width = (max - min) / count;
        int[] tallies = new int[count];
        for (double value : sorted) {
            tallies[Math.min(count - 1, (int) ((value - min) / width))]++;
        }
        for (int i = 0; i < count; i++) {
            // Durations read best as a starting point ("12m"), numbers as a range.
            bins.add(bin(DURATION.equals(unit) ? label(min + i * width, width)
                    : label(min + i * width, width) + "–" + label(min + (i + 1) * width, width), tallies[i]));
        }
        return bins;
    }

    /** A bin edge, precise enough that neighbouring edges read differently. */
    private String label(double value, double width) {
        if (DURATION.equals(unit)) {
            return minutes(value);
        }
        if (Math.abs(value) >= 1000) {
            return Formatting.format(value);
        }
        int places = width >= 1 ? 0 : width >= 0.1 ? 1 : 2;
        return places == 0 ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%." + places + "f", value);
    }

    /** Minutes as "45s", "12m", "3h 20m" or "4d 6h". */
    static String minutes(double minutes) {
        if (minutes < 1) {
            return Math.round(minutes * 60) + "s";
        }
        long whole = Math.round(minutes);
        if (whole < 60) {
            return whole + "m";
        }
        long hours = whole / 60;
        return hours < 48 ? hours + "h" + (whole % 60 == 0 ? "" : " " + whole % 60 + "m")
                : hours / 24 + "d" + (hours % 24 == 0 ? "" : " " + hours % 24 + "h");
    }

    private static Map<String, Object> bin(String name, int count) {
        Map<String, Object> bin = new LinkedHashMap<>();
        bin.put("label", name);
        bin.put("count", count);
        return bin;
    }
}
