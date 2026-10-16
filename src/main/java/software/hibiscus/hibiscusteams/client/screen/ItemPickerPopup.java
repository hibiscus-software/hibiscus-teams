/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import software.hibiscus.hibiscuslib.client.gui.HibiscusTheme;
import software.hibiscus.hibiscuslib.client.gui.widget.ItemSlotWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.TextButton;

// Picks a team icon from the items in the player's inventory (each item once), or no icon. Calls back with the item's
// id, or empty for none.
final class ItemPickerPopup extends TeamPopup {
    private static final int COLUMNS = 9;
    private static final int MAX_ROWS = 5;

    private final Consumer<Optional<Identifier>> onPick;
    private final List<Item> items;

    ItemPickerPopup(Consumer<Optional<Identifier>> onPick) {
        this(onPick, inventoryItems());
    }

    private ItemPickerPopup(Consumer<Optional<Identifier>> onPick, List<Item> items) {
        super(t("icon.title"), COLUMNS * ItemSlotWidget.SIZE + PADDING * 2, height(items.size()));
        this.onPick = onPick;
        this.items = items;
    }

    private static int height(int count) {
        int rows = Math.max(1, Math.min(MAX_ROWS, (count + COLUMNS - 1) / COLUMNS));
        return TOP + rows * ItemSlotWidget.SIZE + 6 + 20 + PADDING;
    }

    // The distinct items the player carries, in inventory order.
    private static List<Item> inventoryItems() {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        Set<Item> items = new LinkedHashSet<>();
        if (player != null) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
        }
        return new ArrayList<>(items).subList(0, Math.min(items.size(), COLUMNS * MAX_ROWS));
    }

    @Override
    protected void init() {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            ItemStack stack = new ItemStack(item);
            add(new ItemSlotWidget(0, 0, stack).onClick(event -> pick(Optional.of(BuiltInRegistries.ITEM.getKey(item)))),
                    PADDING + (i % COLUMNS) * ItemSlotWidget.SIZE, TOP + (i / COLUMNS) * ItemSlotWidget.SIZE);
        }
        int half = (innerWidth() - 4) / 2;
        int y = getHeight() - PADDING - 20;
        add(new TextButton(half, t("icon.none"), button -> pick(Optional.empty())), PADDING, y);
        add(new TextButton(half, CommonComponents.GUI_CANCEL, button -> cancel()), PADDING + half + 4, y);
    }

    private void pick(Optional<Identifier> item) {
        close();
        onPick.accept(item);
    }

    @Override
    protected void extractExtra(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (items.isEmpty()) {
            graphics.text(minecraft().font, TeamIcons.fit(minecraft().font, t("icon.empty"), innerWidth()),
                    getX() + PADDING, getY() + TOP + 5, HibiscusTheme.current().textMuted(), false);
        }
    }
}
