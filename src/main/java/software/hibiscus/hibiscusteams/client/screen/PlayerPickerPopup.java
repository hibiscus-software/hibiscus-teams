/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import software.hibiscus.hibiscuslib.client.gui.widget.ListWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.TextButton;
import software.hibiscus.hibiscuslib.client.gui.widget.TextInput;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamViews;

// The Invite dialog: online players who aren't in (or invited to) the party, filtered by the search field. A name
// that matches nobody online can still be invited, for players who are offline; the server looks it up.
public final class PlayerPickerPopup extends TeamPopup {
    private static final int WIDTH = 220;
    private static final int HEIGHT = 180;
    // What Minecraft allows in a player name.
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final TeamViews.Details team;
    private String search = "";
    private @Nullable UUID selected;
    private final Map<UUID, String> candidates = new LinkedHashMap<>();
    private @Nullable ListWidget<UUID> list;
    private @Nullable TextButton invite;

    public PlayerPickerPopup(TeamViews.Details team) {
        super(t("invite.title"), WIDTH, HEIGHT);
        this.team = team;
    }

    // Online players worth inviting, by name.
    private void findCandidates() {
        candidates.clear();
        ClientPacketListener connection = minecraft().getConnection();
        if (connection == null || minecraft().player == null) {
            return;
        }
        UUID self = minecraft().player.getUUID();
        List<PlayerInfo> players = new ArrayList<>(connection.getOnlinePlayers());
        players.sort(Comparator.comparing(info -> info.getProfile().name().toLowerCase(Locale.ROOT)));
        for (PlayerInfo info : players) {
            UUID id = info.getProfile().id();
            boolean taken = id.equals(self) || team.member(id).isPresent() || team.invites().stream().anyMatch(invite -> invite.id().equals(id));
            if (!taken) {
                candidates.put(id, info.getProfile().name());
            }
        }
    }

    private List<UUID> filtered() {
        String wanted = search.strip().toLowerCase(Locale.ROOT);
        return candidates.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT).contains(wanted))
                .map(Map.Entry::getKey).toList();
    }

    @Override
    protected void init() {
        findCandidates();
        TextInput field = add(new TextInput(innerWidth(), t("invite.search")), PADDING, TOP);
        field.setMaxLength(16);
        field.placeholder(t("invite.search.placeholder"));
        field.setValue(search);
        setFocused(field);
        int listTop = TOP + 18 + 4;
        int buttonsY = getHeight() - PADDING - 20;
        list = add(new ListWidget<>(0, 0, innerWidth(), buttonsY - 6 - listTop, ListWidget.RowRenderer.<UUID>of(
                id -> TeamIcons.face(id, true),
                id -> Component.literal(candidates.getOrDefault(id, "?")),
                id -> null)), PADDING, listTop);
        list.emptyText(t("invite.nobody"));
        list.setRows(filtered());
        if (selected != null && candidates.containsKey(selected)) {
            list.select(selected);
        }
        list.onSelect(id -> {
            selected = id;
            updateInvite();
        });
        list.onActivate(id -> {
            selected = id;
            confirm();
        });
        int half = (innerWidth() - 4) / 2;
        invite = add(new TextButton(half, t("invite.confirm"), button -> confirm()).accent(), PADDING, buttonsY);
        add(new TextButton(half, CommonComponents.GUI_CANCEL, button -> cancel()), PADDING + half + 4, buttonsY);
        field.setResponder(text -> {
            search = text;
            if (list != null) {
                list.setRows(filtered());
                selected = list.selected();
            }
            updateInvite();
        });
        updateInvite();
    }

    // The button invites the selected player, else the typed name.
    private void updateInvite() {
        if (invite == null) {
            return;
        }
        if (selected != null) {
            invite.setMessage(t("invite.confirm_named", candidates.getOrDefault(selected, "?")));
            invite.active = true;
        } else if (NAME.matcher(search.strip()).matches()) {
            invite.setMessage(t("invite.confirm_named", search.strip()));
            invite.active = true;
        } else {
            invite.setMessage(t("invite.confirm"));
            invite.active = false;
        }
    }

    private void confirm() {
        if (selected != null) {
            send(TeamPayloads.Request.of(TeamPayloads.Action.INVITE, selected));
        } else if (NAME.matcher(search.strip()).matches()) {
            send(new TeamPayloads.Request(TeamPayloads.Action.INVITE_NAME, java.util.Optional.empty(), search.strip()));
        } else {
            return;
        }
        close();
    }
}
