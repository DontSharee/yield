package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.AchievementService;
import me.dontshare.yieldachievements.data.AchievementDefinition;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Paginated list of every achievement - credits are auto-granted on completion, so there's no claim step here, just a completed/in-progress state. */
public final class AchievementsGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_ROWS = TOTAL_ROWS - 1;
    /** Centred rows of seven inside the border - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(CONTENT_ROWS);
    private static final int PREV_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final Supplier<Map<String, AchievementDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final AchievementService service;
    private final GuiManager guiManager;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public AchievementsGui(Supplier<Map<String, AchievementDefinition>> content, PlayerDataStore<PackPlayerProfile> store,
                            AchievementService service, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(id);
        List<AchievementDefinition> all = new ArrayList<>(content.get().values());
        Page<AchievementDefinition> page = Page.of(all, pageIndex.getOrDefault(id, 0), PAGE_SIZE);
        pageIndex.put(id, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Achievements");
        List<AchievementDefinition> items = page.items();
        builder.fill(java.util.stream.IntStream.range(0, 45), GuiIcons.filler());
        int[] contentSlots = GuiLayout.centered(0, items.size());
        for (int i = 0; i < items.size(); i++) {
            AchievementDefinition def = items.get(i);
            builder.item(contentSlots[i], buildIcon(profile, def));
        }
        builder.fill(java.util.stream.IntStream.range(45, 54).filter(s -> s != PREV_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        pageIndex.put(player.getUniqueId(), pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        open(player);
    }

    private ItemStack buildIcon(PackPlayerProfile profile, AchievementDefinition def) {
        boolean complete = service.isComplete(profile, def);
        long progress = service.progressOf(profile, def);

        ItemBuilder builder = ItemBuilder.of(def.icon())
                .name((complete ? "&a&l✔ " : "&f") + def.displayName());

        List<String> description = new ArrayList<>();
        for (String line : def.description()) {
            description.add(" &7" + line);
        }
        String progressLine = "Progress: " + (complete ? "&a&lCOMPLETE"
                : MenuLore.progress(progress, def.goal()));
        String rewardLine = "Reward: &6" + Formatting.format(def.rewardCredits()) + " &7Credits";
        MenuLore.info("achievement", description, MenuLore.ACCENT, List.of(progressLine, rewardLine)).forEach(builder::lore);

        if (complete) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }
}
