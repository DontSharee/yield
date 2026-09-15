package me.dontshare.yieldcosmetics.data;



import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The three cosmetic slots on {@link CosmeticProfile} - each one's
 * accessor pair, config section, and Mongo field path (for {@code
 * CosmeticPopularityService}'s "X players have this" counts) live together
 * here so nothing else needs a switch over the category.
 */
public enum CosmeticCategory {
    CHAT_COLOR("Chat Colors", "chat-colors", "cosmetics.equippedChatColor",
            CosmeticProfile::getEquippedChatColor, CosmeticProfile::setEquippedChatColor),
    NAMEPLATE("Nameplates", "nameplates", "cosmetics.equippedNameplate",
            CosmeticProfile::getEquippedNameplate, CosmeticProfile::setEquippedNameplate),
    TAG("Tags", "tags", "cosmetics.equippedTag",
            CosmeticProfile::getEquippedTag, CosmeticProfile::setEquippedTag);

    private final String displayName;
    private final String configSection;
    private final String mongoField;
    private final Function<CosmeticProfile, String> getter;
    private final BiConsumer<CosmeticProfile, String> setter;

    CosmeticCategory(String displayName, String configSection, String mongoField,
                      Function<CosmeticProfile, String> getter, BiConsumer<CosmeticProfile, String> setter) {
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

    public String equippedId(CosmeticProfile profile) {
        return getter.apply(profile);
    }

    public void setEquippedId(CosmeticProfile profile, String cosmeticId) {
        setter.accept(profile, cosmeticId);
    }
}
