package me.dontshare.yieldcore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/** Fluent builder for a {@link Gui} - declare every slot's item and (optionally) its click behavior, then {@link #build()}. */
public final class GuiBuilder {

    private final int rows;
    private final Component title;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    private final Map<Integer, GuiClickHandler> handlers = new HashMap<>();

    GuiBuilder(int rows, Component title) {
        this.rows = rows;
        this.title = title;
    }

    /** A static, non-interactive slot (a border pane, decoration, info display, etc). */
    public GuiBuilder item(int slot, ItemStack item) {
        items.put(slot, item);
        return this;
    }

    public GuiBuilder item(int slot, ItemStack item, GuiClickHandler onClick) {
        items.put(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    public GuiBuilder fill(IntStream slots, ItemStack item) {
        slots.forEach(slot -> item(slot, item));
        return this;
    }

    public GuiBuilder fill(IntStream slots, ItemStack item, GuiClickHandler onClick) {
        slots.forEach(slot -> item(slot, item, onClick));
        return this;
    }

    public Gui build() {
        return new Gui(rows, title, items, handlers);
    }
}
