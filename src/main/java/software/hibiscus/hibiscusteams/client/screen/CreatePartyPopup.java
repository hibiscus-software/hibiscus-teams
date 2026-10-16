/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import software.hibiscus.hibiscuslib.client.gui.BaseScreen;
import software.hibiscus.hibiscuslib.client.gui.widget.ColorPicker;
import software.hibiscus.hibiscuslib.client.gui.widget.ItemSlotWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.TextButton;
import software.hibiscus.hibiscuslib.client.gui.widget.TextInput;
import software.hibiscus.hibiscuslib.client.gui.widget.ToggleButton;
import software.hibiscus.hibiscusteams.client.ClientTeamState;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamViews;

// The Create party dialog: a name (checked against the teams the client knows; the server checks again), a colour,
// an icon picked from the inventory, and free to join. Sends CreateParty.
public final class CreatePartyPopup extends TeamPopup {
    private static final int WIDTH = 220;

    private String name = "";
    private int color = ColorPicker.PRESETS[ThreadLocalRandom.current().nextInt(ColorPicker.PRESETS.length)];
    private Optional<Identifier> icon = Optional.empty();
    private boolean freeToJoin;

    public CreatePartyPopup() {
        super(t("create.title"), WIDTH, TOP + 18 + 6 + ColorPicker.HEIGHT + 6 + 18 + 4 + 16 + 8 + 20 + PADDING);
    }

    private static TeamViews.ServerInfo info() {
        return ClientTeamState.view().map(TeamViews.PlayerView::info).orElse(TeamViews.ServerInfo.DEFAULT);
    }

    // 1 to nameMaxLength characters, and no other known team with that name (any case).
    static boolean nameValid(String text, java.util.UUID except) {
        String name = text.strip();
        if (name.isEmpty() || name.length() > info().nameMaxLength() || name.indexOf('§') >= 0) {
            return false;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        return ClientTeamState.summaries().stream()
                .noneMatch(summary -> !summary.id().equals(except) && summary.name().toLowerCase(Locale.ROOT).equals(wanted));
    }

    @Override
    protected void init() {
        int y = TOP;
        TextButton create = new TextButton(0, 0, (innerWidth() - 4) / 2, 20, t("create.confirm"), button -> {
            send(new TeamPayloads.CreateParty(name.strip(), color, icon, freeToJoin));
            close();
        }).accent();
        TextInput nameInput = add(new TextInput(innerWidth(), t("create.name")), PADDING, y);
        nameInput.setMaxLength(info().nameMaxLength());
        nameInput.placeholder(t("create.name.placeholder"));
        nameInput.setValue(name);
        nameInput.validator(text -> text.isEmpty() || nameValid(text, new java.util.UUID(0, 0)));
        nameInput.setResponder(text -> {
            name = text;
            create.active = nameValid(text, new java.util.UUID(0, 0));
        });
        setFocused(nameInput);
        y += 18 + 6;
        add(new ColorPicker(0, 0, color, rgb -> color = rgb), PADDING, y);
        y += ColorPicker.HEIGHT + 6;
        add(new ItemSlotWidget(0, 0, () -> TeamIcons.stack(icon).orElse(ItemStack.EMPTY))
                .onClick(event -> {
                    if (screen() instanceof BaseScreen screen) {
                        screen.openPopup(new ItemPickerPopup(picked -> icon = picked));
                    }
                }).tooltip(t("create.icon")), PADDING, y);
        add(new Label(innerWidth() - ItemSlotWidget.SIZE - 6, t("create.icon.label"), true), PADDING + ItemSlotWidget.SIZE + 6, y + 5);
        y += ItemSlotWidget.SIZE + 4;
        if (info().allowFreeToJoin()) {
            add(new ToggleButton(0, 0, innerWidth(), t("settings.free_to_join"), freeToJoin, value -> freeToJoin = value), PADDING, y);
        }
        int buttonsY = getHeight() - PADDING - 20;
        add(create, PADDING, buttonsY);
        create.active = nameValid(name, new java.util.UUID(0, 0));
        add(new TextButton((innerWidth() - 4) / 2, CommonComponents.GUI_CANCEL, button -> cancel()), PADDING + (innerWidth() - 4) / 2 + 4, buttonsY);
    }
}
