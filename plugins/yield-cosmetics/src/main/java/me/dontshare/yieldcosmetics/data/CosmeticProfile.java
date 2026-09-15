package me.dontshare.yieldcosmetics.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * This plugin's own slice of a player's data - which cosmetic is equipped in
 * each of the three slots, or null for none.
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class CosmeticProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private String equippedChatColor;
    private String equippedNameplate;
    private String equippedTag;

    public CosmeticProfile() {
    }

    public CosmeticProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getEquippedChatColor() {
        return equippedChatColor;
    }

    public void setEquippedChatColor(String equippedChatColor) {
        this.equippedChatColor = equippedChatColor;
    }

    public String getEquippedNameplate() {
        return equippedNameplate;
    }

    public void setEquippedNameplate(String equippedNameplate) {
        this.equippedNameplate = equippedNameplate;
    }

    public String getEquippedTag() {
        return equippedTag;
    }

    public void setEquippedTag(String equippedTag) {
        this.equippedTag = equippedTag;
    }
}
