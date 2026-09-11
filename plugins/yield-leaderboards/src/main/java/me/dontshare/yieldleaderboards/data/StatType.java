package me.dontshare.yieldleaderboards.data;

import me.dontshare.yieldcore.text.Formatting;

import java.math.BigInteger;

/**
 * How to parse/compare/format a stat's raw BSON value. {@code BIGINT_STRING}
 * exists because currency fields are stored as BSON strings for unbounded
 * precision (see the custom {@code BigIntegerCodec} in yield-core) - a
 * native Mongo sort on those would be lexicographic ("10" before "9"),
 * which is wrong, so every stat is ranked by parsing to a {@link
 * Comparable} in Java rather than relying on the database's own sort.
 */
public enum StatType {
    BIGINT_STRING {
        @Override
        public Comparable<BigInteger> parse(Object raw) {
            return raw instanceof String s ? new BigInteger(s) : BigInteger.ZERO;
        }

        @Override
        public String format(Comparable<?> value) {
            return Formatting.format((BigInteger) value);
        }
    },
    LONG {
        @Override
        public Comparable<Long> parse(Object raw) {
            return raw instanceof Number n ? n.longValue() : 0L;
        }

        @Override
        public String format(Comparable<?> value) {
            return Formatting.format((Long) value);
        }
    },
    INT {
        @Override
        public Comparable<Integer> parse(Object raw) {
            return raw instanceof Number n ? n.intValue() : 0;
        }

        @Override
        public String format(Comparable<?> value) {
            return String.valueOf(value);
        }
    };

    /** Parses a raw value already navigated out of the document (see {@code LeaderboardService#resolvePath}) - never null, defaults to zero. */
    public abstract Comparable<?> parse(Object raw);

    /** Formats an already-parsed value for hologram display. */
    public abstract String format(Comparable<?> value);
}
