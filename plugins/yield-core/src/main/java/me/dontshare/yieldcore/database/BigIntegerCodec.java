package me.dontshare.yieldcore.database;

import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The MongoDB driver has a built-in codec for {@link BigDecimal} but not
 * {@link BigInteger} - registered in {@link DatabaseManager} so any POJO
 * field of this type (e.g. currency balances that must never overflow a
 * fixed-width numeric type) works automatically, with no per-field
 * annotation needed.
 * <p>
 * Always writes as a BSON string (so growth is never bounded by a numeric
 * BSON type's own limits), but reads tolerantly from a string, int32,
 * int64, or double - the last two cover every value already saved under a
 * plain {@code long} field before it was migrated to this type, so
 * existing player data loads correctly with no manual migration; it's
 * simply re-saved in the canonical string form the next time that
 * document is written.
 */
public final class BigIntegerCodec implements Codec<BigInteger> {

    @Override
    public void encode(BsonWriter writer, BigInteger value, EncoderContext encoderContext) {
        writer.writeString(value.toString());
    }

    @Override
    public BigInteger decode(BsonReader reader, DecoderContext decoderContext) {
        BsonType type = reader.getCurrentBsonType();
        return switch (type) {
            case STRING -> new BigInteger(reader.readString());
            case INT32 -> BigInteger.valueOf(reader.readInt32());
            case INT64 -> BigInteger.valueOf(reader.readInt64());
            case DOUBLE -> BigDecimal.valueOf(reader.readDouble()).toBigInteger();
            default -> throw new IllegalStateException("Unsupported BSON type for BigInteger: " + type);
        };
    }

    @Override
    public Class<BigInteger> getEncoderClass() {
        return BigInteger.class;
    }
}
