/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.client.gui.widget.ListWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.TextButton;
import software.hibiscus.hibiscuslib.client.gui.widget.TextInput;
import software.hibiscus.hibiscusteams.client.ClientTeamState;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamViews;

// The Add ally dialog: other parties (not already allies or enemies), filtered by the search field, to ask to be
// allies or mark as an enemy.
public final class TeamPickerPopup extends TeamPopup {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 180;

    private final TeamViews.Details team;
    private String search = "";
    private @Nullable UUID selected;
    private @Nullable ListWidget<UUID> list;
    private @Nullable TextButton ally;
    private @Nullable TextButton enemy;

    public TeamPickerPopup(TeamViews.Details team) {
        super(t("ally.title"), WIDTH, HEIGHT);
        this.team = team;
    }

    private List<UUID> filtered() {
        String wanted = search.strip().toLowerCase(Locale.ROOT);
        UUID own = team.summary().id();
        return ClientTeamState.summaries().stream()
                .filter(summary -> summary.type() == TeamType.PARTY && !summary.id().equals(own)
                        && !team.allies().contains(summary.id()) && !team.enemies().contains(summary.id())
                        && summary.name().toLowerCase(Locale.ROOT).contains(wanted))
                .sorted(Comparator.comparing(summary -> summary.name().toLowerCase(Locale.ROOT)))
                .map(TeamViews.Summary::id).toList();
    }

    @Override
    protected void init() {
        TextInput field = add(new TextInput(innerWidth(), t("ally.search")), PADDING, TOP);
        field.setMaxLength(32);
        field.placeholder(t("ally.search.placeholder"));
        field.setValue(search);
        setFocused(field);
        int listTop = TOP + 18 + 4;
        int buttonsY = getHeight() - PADDING - 20;
        list = add(new ListWidget<>(0, 0, innerWidth(), buttonsY - 6 - listTop, ListWidget.RowRenderer.<UUID>of(
                id -> ClientTeamState.summary(id).map(TeamIcons::team).orElse(software.hibiscus.hibiscuslib.client.gui.Icon.NONE),
                id -> ClientTeamState.summary(id).map(TeamIcons::name).orElse(Component.empty()),
                id -> ClientTeamState.summary(id).map(summary -> summary.allowAllyInvites()
                        ? t("ally.members", summary.memberCount())
                        : t("ally.closed", summary.memberCount())).orElse(null))), PADDING, listTop);
        list.emptyText(t("ally.nobody"));
        list.setRows(filtered());
        if (selected != null && list.rows().contains(selected)) {
            list.select(selected);
        }
        list.onSelect(id -> {
            selected = id;
            updateButtons();
        });
        int third = (innerWidth() - 8) / 3;
        ally = add(new TextButton(third, t("ally.request"), button -> confirm(TeamPayloads.Action.ALLY_REQUEST)).accent(), PADDING, buttonsY);
        enemy = add(new TextButton(third, t("ally.enemy"), button -> confirm(TeamPayloads.Action.ENEMY_ADD)), PADDING + third + 4, buttonsY);
        add(new TextButton(third, CommonComponents.GUI_CANCEL, button -> cancel()), PADDING + (third + 4) * 2, buttonsY);
        field.setResponder(text -> {
            search = text;
            if (list != null) {
                list.setRows(filtered());
                selected = list.selected();
            }
            updateButtons();
        });
        updateButtons();
    }

    private void updateButtons() {
        boolean open = selected != null && ClientTeamState.summary(selected).map(TeamViews.Summary::allowAllyInvites).orElse(false);
        if (ally != null) {
            ally.active = open;
        }
        if (enemy != null) {
            enemy.active = selected != null;
        }
    }

    private void confirm(TeamPayloads.Action action) {
        if (selected != null) {
            send(TeamPayloads.Request.of(action, selected));
            close();
        }
    }
}
