package me.dontshare.yieldcosmetics.data;

import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The three cosmetic slots on {@link PackPlayerProfile} - each one's
 * accessor pair, config section, and Mongo field path (for {@code
 * CosmeticPopularityService}'s "X players have this" counts) live together
 * here so nothing else needs a switch over the category.
 */
public enum CosmeticCategory {
    CHAT_COLOR("Chat Colors", "chat-colors", "packs.equippedChatColor",
            PackPlayerProfile::getEquippedChatColor, PackPlayerProfile::setEquippedChatColor),
    NAMEPLATE("Nameplates", "nameplates", "packs.equippedNameplate",
            PackPlayerProfile::getEquippedNameplate, PackPlayerProfile::setEquippedNameplate),
    TAG("Tags", "tags", "packs.equippedTag",
            PackPlayerProfile::getEquippedTag, PackPlayerProfile::setEquippedTag);

    private final String displayName;
    private final String configSection;
    private final String mongoField;
    private final Function<PackPlayerProfile, String> getter;
    private final BiConsumer<PackPlayerProfile, String> setter;

    CosmeticCategory(String displayName, String configSection, String mongoField,
                      Function<PackPlayerProfile, String> getter, BiConsumer<PackPlayerProfile, String> setter) {
        this.displayName = displayName;
        this.configSection = configSection;
        this.mongoField = mongoField;
        this.getter = getter;
        this.setter = setter;
    }

    public String displayName() {
        return displayName;
    }

    public String configSection() {
        return configSection;
    }

    public String mongoField() {
        return mongoField;
    }

    public String equippedId(PackPlayerProfile profile) {
        return getter.apply(profile);
    }

    public void setEquippedId(PackPlayerProfile profile, String cosmeticId) {
        setter.accept(profile, cosmeticId);
    }
}
