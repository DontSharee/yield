package me.dontshare.yield.database;

import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * Marker for POJOs stored via {@link PlayerDataStore}. Implementations
 * must annotate their UUID field with {@link BsonId} so it's stored as
 * the document's {@code _id} regardless of the field's name - relying on
 * the POJO codec's default "field named id" convention is fragile, so
 * this is required rather than assumed.
 */
public interface PlayerRecord {

    UUID getPlayerId();
}
