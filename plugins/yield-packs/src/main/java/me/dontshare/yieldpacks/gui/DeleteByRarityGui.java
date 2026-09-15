package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Bulk pet cleanup - one icon per configured rarity (whatever exists in
 * {@link RarityRegistry}, no hardcoded tier list), each showing how many
 * unequipped copies of that rarity are sitting in storage. A click deletes
 * every one of them at once - equipped pets are always protected, the same
 * "owned minus equipped" floor {@code FusionService}/{@code BagGui} already
 * apply elsewhere.
 */
public final class DeleteByRarityGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 3;
    private static final int RARITY_ROW_START = 9;
    private static final int CLOSE_SLOT = 22;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final GuiManager guiManager;

    public DeleteByRarityGui(PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                              Supplier<RarityRegistry> rarityRegistry, GuiManager guiManager) {
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<Rarity> rarities = rarityRegistry.get().all().stream()
                .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                .toList();

        var builder = Gui.builder(TOTAL_ROWS, "Delete by Rarity");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9).filter(slot -> slot != CLOSE_SLOT), GuiIcons.filler());

        for (int i = 0; i < rarities.size() && i < 9; i++) {
            Rarity rarity = rarities.get(i);
            int owned = unequippedCountFor(profile, rarity.id());
            builder.item(RARITY_ROW_START + i, buildRarityIcon(rarity, owned),
                    (clicker, event) -> deleteRarity(clicker, rarity));
        }

        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    /** Only baseline, unequipped copies count/are deleted - a leveled or candy-fed pet is never bulk-deletable this way. */
    private int unequippedCountFor(PackPlayerProfile profile, String rarityId) {
        int total = 0;
        for (PetInstance pet : profile.getPets()) {
            if (!pet.isBaseline() || profile.isEquipped(pet.getInstanceId())) {
                continue;
            }
            ItemDefinition item = itemRegistry.get().find(pet.getItemId()).orElse(null);
            if (item != null && item.rarityId().equals(rarityId)) {
                total++;
            }
        }
        return total;
    }

    private void deleteRarity(Player player, Rarity rarity) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int deleted = 0;
        var it = profile.getPets().iterator();
        while (it.hasNext()) {
            PetInstance pet = it.next();
            if (!pet.isBaseline() || profile.isEquipped(pet.getInstanceId())) {
                continue;
            }
            ItemDefinition item = itemRegistry.get().find(pet.getItemId()).orElse(null);
            if (item == null || !item.rarityId().equals(rarity.id())) {
                continue;
            }
            it.remove();
            deleted++;
        }
        store.save(player.getUniqueId());
        if (deleted > 0) {
            player.sendMessage(Text.parse("<gray>Deleted <count>x <rarity> pet(s).</gray>",
                    Placeholder.unparsed("count", String.valueOf(deleted)),
                    Placeholder.unparsed("rarity", Formatting.stripLeadingColorCodes(rarity.displayName()))));
        } else {
            player.sendMessage(Text.parse("<red>You have no unequipped pets of that rarity.</red>"));
        }
        open(player);
    }

    private ItemStack buildRarityIcon(Rarity rarity, int owned) {
        String accent = "<" + rarity.colorHex() + ">";
        String plainName = Formatting.stripLeadingColorCodes(rarity.displayName()).toUpperCase();
        ItemBuilder builder = ItemBuilder.of(Material.BONE).name(MenuLore.buttonName(accent, plainName));
        List<String> data = new ArrayList<>(List.of("&7Owned: &f" + owned));
        MenuLore.button(
                "cleanup",
                List.of(" &7Deletes every unequipped", " &7pet of this rarity at once.", " &7Equipped pets are protected."),
                accent,
                owned > 0 ? "Click to Delete" : "Nothing to Delete"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
