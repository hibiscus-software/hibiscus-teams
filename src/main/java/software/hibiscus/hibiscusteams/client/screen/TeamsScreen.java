/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.client.HibiscusClientNetwork;
import software.hibiscus.hibiscuslib.client.gui.BaseScreen;
import software.hibiscus.hibiscuslib.client.gui.HibiscusTheme;
import software.hibiscus.hibiscuslib.client.gui.Icon;
import software.hibiscus.hibiscuslib.client.gui.popup.ConfirmDialog;
import software.hibiscus.hibiscuslib.client.gui.popup.ContextMenu;
import software.hibiscus.hibiscuslib.client.gui.widget.ColorPicker;
import software.hibiscus.hibiscuslib.client.gui.widget.ItemSlotWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.ListWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.Panel;
import software.hibiscus.hibiscuslib.client.gui.widget.ScrollPanel;
import software.hibiscus.hibiscuslib.client.gui.widget.Tabs;
import software.hibiscus.hibiscuslib.client.gui.widget.TextButton;
import software.hibiscus.hibiscuslib.client.gui.widget.TextInput;
import software.hibiscus.hibiscuslib.client.gui.widget.TextLayoutWidget;
import software.hibiscus.hibiscuslib.client.gui.widget.ToggleButton;
import software.hibiscus.hibiscusteams.client.ClientTeamState;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamPayloads.Action;
import software.hibiscus.hibiscusteams.network.TeamViews;
import software.hibiscus.hibiscusteams.team.TeamColors;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// The Teams screen, opened from the inventory button: a compact panel that shows either "you're on your own" (create
// or join a party, answer invites) or the player's party or server team (Members, Allies and Settings tabs). It only
// shows what the server synced and sends requests; the server decides and the sync brings the result back, at which
// point the screen rebuilds itself (keeping the tab, selections and unsaved settings).
public class TeamsScreen extends BaseScreen {
    private static final int MEMBERS = 0;
    private static final int ALLIES = 1;
    private static final int SETTINGS = 2;
    private static final long STATUS_MS = 6000;
    private static final Pattern TAG = Pattern.compile("[^\\s§]{1," + TeamSetting.TAG_MAX_LENGTH + "}");

    // Where a team in the Allies tab stands with the player's team.
    private enum Relation {
        REQUEST_IN,
        ALLY,
        REQUEST_OUT,
        ENEMY
    }

    private int tab;
    private @Nullable UUID selectedRow;
    private @Nullable UUID selectedMember;
    private @Nullable UUID selectedTeam;
    // Unsaved settings, for the team in draftTeam.
    private final Map<TeamSetting, String> draft = new EnumMap<>(TeamSetting.class);
    private final Map<TeamSetting, Boolean> invalid = new EnumMap<>(TeamSetting.class);
    private @Nullable UUID draftTeam;
    private boolean saving;
    private Component status = Component.empty();
    private boolean statusOk;
    private long statusUntil;
    private @Nullable TextButton saveButton;

    public TeamsScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.hibiscusteams.title"), parent);
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gui.setScreen(new TeamsScreen(minecraft.gui.screen()));
    }

    static Component t(String key, Object... args) {
        return Component.translatable("screen.hibiscusteams." + key, args);
    }

    private static void send(CustomPacketPayload payload) {
        HibiscusClientNetwork.sendToServer(payload);
    }

    private static void request(Action action, UUID target) {
        send(TeamPayloads.Request.of(action, target));
    }

    @Override
    protected int preferredWidth() {
        return 260;
    }

    @Override
    protected int preferredHeight() {
        return 200;
    }

    // ---- Called by ClientTeamState ----

    // The synced teams changed: rebuild with the new data.
    public void refresh() {
        rebuildWidgets();
    }

    public void showResult(boolean ok, Component message) {
        status = message;
        statusOk = ok;
        statusUntil = Util.getMillis() + STATUS_MS;
        if (saving) {
            saving = false;
            if (ok) {
                draft.clear();
                invalid.clear();
            }
            rebuildWidgets();
        }
    }

    // Shows a tab (the tab row, and the screenshot harness).
    public void showTab(int index) {
        tab = index;
        rebuildWidgets();
    }

    // For the screenshot harness: a member's context menu, and the disband dialog, as a click would open them.
    public void showMemberMenu(UUID member) {
        ClientTeamState.view().ifPresent(view -> openPopup(memberMenu(view.mine(), member, panelX + 60, panelY + 80)));
    }

    public void showDisband() {
        ClientTeamState.view().ifPresent(view -> confirmDisband(view.mine()));
    }

    // ---- Building ----

    @Override
    protected void initContent() {
        Optional<TeamViews.PlayerView> view = ClientTeamState.view();
        saveButton = null;
        if (view.isEmpty()) {
            return;
        }
        if (view.get().mine().type().isPlayer()) {
            initSolo(view.get());
        } else {
            initTeam(view.get());
        }
    }

    private int left() {
        return panelX + 6;
    }

    private int innerWidth() {
        return panelWidth - 12;
    }

    private int bottom() {
        return panelY + panelHeight - 6;
    }

    // -- On your own --

    private void initSolo(TeamViews.PlayerView view) {
        int x = left();
        int width = innerWidth();
        int y = panelY + HEADER + 24;
        int half = (width - 4) / 2;

        // Invites first, then parties anyone can join.
        Map<UUID, TeamViews.Pending> invites = new LinkedHashMap<>();
        view.invites().forEach(invite -> invites.put(invite.id(), invite));
        List<UUID> rows = new ArrayList<>(invites.keySet());
        ClientTeamState.summaries().stream()
                .filter(summary -> summary.type() == TeamType.PARTY && summary.freeToJoin() && !invites.containsKey(summary.id()))
                .sorted(Comparator.comparing(summary -> summary.name().toLowerCase(Locale.ROOT)))
                .forEach(summary -> rows.add(summary.id()));

        TextButton create = addRenderableWidget(new TextButton(x, y, half, 20, t("solo.create"), button -> openPopup(new CreatePartyPopup())).accent());
        create.active = view.info().partiesEnabled();
        if (!create.active) {
            create.withTooltip(t("solo.parties_disabled"));
        }
        TextButton join = addRenderableWidget(new TextButton(x + half + 4, y, half, 20, t("solo.join"), button -> joinSelected(invites)));
        int listTop = y + 20 + 14;
        int buttonsY = bottom() - 20;
        TextButton decline = addRenderableWidget(new TextButton(x + width - half, buttonsY, half, 20, t("solo.decline"), button -> {
            if (selectedRow != null) {
                request(Action.DECLINE, selectedRow);
            }
        }));
        ListWidget<UUID> list = addRenderableWidget(new ListWidget<>(x, listTop, width, buttonsY - 4 - listTop, ListWidget.RowRenderer.<UUID>of(
                id -> ClientTeamState.summary(id).map(TeamIcons::team).orElse(Icon.NONE),
                id -> ClientTeamState.summary(id).map(TeamIcons::name).orElse(Component.literal("?")),
                id -> {
                    int members = ClientTeamState.summary(id).map(TeamViews.Summary::memberCount).orElse(0);
                    TeamViews.Pending invite = invites.get(id);
                    if (invite == null) {
                        return t("solo.open_row", members);
                    }
                    return invite.name().isEmpty() ? t("solo.invite_row_anonymous", members) : t("solo.invite_row", invite.name(), members);
                })));
        list.emptyText(t("solo.empty"));
        list.setRows(rows);
        if (selectedRow != null && rows.contains(selectedRow)) {
            list.select(selectedRow);
        }
        Runnable update = () -> {
            boolean invited = selectedRow != null && invites.containsKey(selectedRow);
            join.active = selectedRow != null;
            join.setMessage(invited ? t("solo.accept") : t("solo.join"));
            decline.visible = invited;
        };
        list.onSelect(id -> {
            selectedRow = id;
            update.run();
        });
        list.onActivate(id -> {
            selectedRow = id;
            joinSelected(invites);
        });
        update.run();
        if (!join.active) {
            join.withTooltip(t("solo.join.hint"));
        }
    }

    private void joinSelected(Map<UUID, TeamViews.Pending> invites) {
        if (selectedRow != null) {
            request(invites.containsKey(selectedRow) ? Action.ACCEPT : Action.JOIN, selectedRow);
        }
    }

    // -- A party or server team --

    private void initTeam(TeamViews.PlayerView view) {
        TeamViews.Details mine = view.mine();
        int tabsY = panelY + 30;
        addRenderableWidget(new Tabs(left(), tabsY, innerWidth(), List.of(
                Tabs.Tab.of(t("tab.members")), Tabs.Tab.of(t("tab.allies")), Tabs.Tab.of(t("tab.settings"))), this::showTab)).setSelected(tab);
        int top = tabsY + Tabs.HEIGHT + 4;
        switch (tab) {
            case MEMBERS -> initMembers(view, top);
            case ALLIES -> initAllies(view, top);
            default -> initSettings(view, top);
        }
    }

    private void initMembers(TeamViews.PlayerView view, int top) {
        TeamViews.Details mine = view.mine();
        int buttonsY = bottom() - 20;
        List<UUID> rows = new ArrayList<>();
        mine.members().stream()
                .sorted(Comparator.comparing((TeamViews.Member member) -> -member.rank().ordinal())
                        .thenComparing(member -> member.name().toLowerCase(Locale.ROOT)))
                .forEach(member -> rows.add(member.id()));
        mine.invites().forEach(invite -> rows.add(invite.id()));
        UUID self = minecraft.player != null ? minecraft.player.getUUID() : null;

        ListWidget<UUID> list = addRenderableWidget(new ListWidget<>(left(), top, innerWidth(), buttonsY - 4 - top, ListWidget.RowRenderer.<UUID>of(
                id -> TeamIcons.face(id, mine.member(id).map(TeamViews.Member::online).orElse(false)),
                id -> {
                    String name = mine.member(id).map(TeamViews.Member::name).orElseGet(() -> invite(mine, id).map(TeamViews.Pending::name).orElse("?"));
                    return id.equals(self) ? t("members.you", name) : Component.literal(name);
                },
                id -> mine.member(id)
                        .map(member -> (Component) rankName(member.rank()))
                        .orElseGet(() -> invite(mine, id).map(invite -> t("members.invited", TeamIcons.expiry(invite.expires()))).orElse(null)))));
        list.setRows(rows);
        if (selectedMember != null && rows.contains(selectedMember)) {
            list.select(selectedMember);
        }
        list.onSelect(id -> selectedMember = id);
        list.onContextMenu((id, x, y) -> openPopup(memberMenu(mine, id, x, y)));

        boolean canInvite = mine.type() == TeamType.PARTY && mine.myRank().isAtLeast(TeamRank.OFFICER);
        TextButton invite = addRenderableWidget(new TextButton(left(), buttonsY, 90, 20, t("members.invite"),
                button -> openPopup(new PlayerPickerPopup(mine))));
        invite.active = canInvite;
        if (!canInvite) {
            invite.withTooltip(t(mine.type() == TeamType.PARTY ? "members.invite.officers" : "members.invite.server"));
        }
        int toggleWidth = Math.min(120, innerWidth() - 96);
        addRenderableWidget(new ToggleButton(left() + innerWidth() - toggleWidth, buttonsY + 2, toggleWidth, t("members.team_chat"),
                mine.teamChat(), value -> send(TeamPayloads.Request.of(Action.TEAM_CHAT)))).withTooltip(t("members.team_chat.tooltip"));
    }

    private static Optional<TeamViews.Pending> invite(TeamViews.Details mine, UUID id) {
        return mine.invites().stream().filter(invite -> invite.id().equals(id)).findFirst();
    }

    static Component rankName(TeamRank rank) {
        return Component.translatable("hibiscusteams.rank." + rank.name().toLowerCase(Locale.ROOT));
    }

    // What the player may do to a member, by rank (the server checks again).
    private ContextMenu memberMenu(TeamViews.Details mine, UUID id, int x, int y) {
        ContextMenu.Builder menu = ContextMenu.builder();
        UUID self = minecraft.player != null ? minecraft.player.getUUID() : null;
        TeamRank me = mine.myRank();
        boolean party = mine.type() == TeamType.PARTY;
        Optional<TeamViews.Member> member = mine.member(id);
        int items = 0;
        if (member.isEmpty()) {
            if (party && me.isAtLeast(TeamRank.OFFICER)) {
                menu.addDanger(t("menu.withdraw"), Icon.NONE, () -> request(Action.KICK, id));
                items++;
            }
        } else {
            String name = member.get().name();
            TeamRank rank = member.get().rank();
            boolean isSelf = id.equals(self);
            if (!isSelf && member.get().online()) {
                menu.add(t("menu.message", name), () -> minecraft.gui.openChatAndAddText(ChatComponent.ChatMethod.MESSAGE, "/msg " + name + " "));
                items++;
            }
            if (party && me == TeamRank.OWNER && !isSelf) {
                if (rank == TeamRank.MEMBER) {
                    menu.add(t("menu.promote"), () -> request(Action.PROMOTE, id));
                } else if (rank == TeamRank.OFFICER) {
                    menu.add(t("menu.demote"), () -> request(Action.DEMOTE, id));
                }
                menu.add(t("menu.transfer"), () -> openPopup(new ConfirmDialog(t("confirm.transfer.title", name),
                        t("confirm.transfer.message", name), t("confirm.transfer.confirm"), CommonComponents.GUI_CANCEL,
                        yes -> {
                            if (yes) {
                                request(Action.TRANSFER, id);
                            }
                        })));
                items += 2;
            }
            if (party && !isSelf && me.isAtLeast(TeamRank.OFFICER) && rank.ordinal() < me.ordinal()) {
                menu.separator();
                menu.addDanger(t("menu.kick"), Icon.NONE, () -> openPopup(new ConfirmDialog(t("confirm.kick.title", name),
                        t("confirm.kick.message", name), t("confirm.kick.confirm"), CommonComponents.GUI_CANCEL,
                        yes -> {
                            if (yes) {
                                request(Action.KICK, id);
                            }
                        }).dangerous()));
                items++;
            }
        }
        if (items == 0) {
            menu.addDisabled(t("menu.nothing"), Icon.NONE);
        }
        return menu.build(x, y);
    }

    private void initAllies(TeamViews.PlayerView view, int top) {
        TeamViews.Details mine = view.mine();
        int buttonsY = bottom() - 20;
        Map<UUID, Relation> relations = new LinkedHashMap<>();
        mine.allyRequestsIn().forEach(id -> relations.put(id, Relation.REQUEST_IN));
        mine.allies().forEach(id -> relations.put(id, Relation.ALLY));
        mine.allyRequestsOut().forEach(id -> relations.putIfAbsent(id, Relation.REQUEST_OUT));
        mine.enemies().forEach(id -> relations.putIfAbsent(id, Relation.ENEMY));
        relations.keySet().removeIf(id -> ClientTeamState.summary(id).isEmpty());
        boolean officer = mine.type() == TeamType.PARTY && mine.myRank().isAtLeast(TeamRank.OFFICER);

        ListWidget<UUID> list = addRenderableWidget(new ListWidget<>(left(), top, innerWidth(), buttonsY - 4 - top, ListWidget.RowRenderer.<UUID>of(
                id -> ClientTeamState.summary(id).map(TeamIcons::team).orElse(Icon.NONE),
                id -> ClientTeamState.summary(id).map(TeamIcons::name).orElse(Component.literal("?")),
                id -> t("allies." + relations.get(id).name().toLowerCase(Locale.ROOT)))));
        list.emptyText(t(mine.type() == TeamType.PARTY ? (view.info().alliesEnabled() ? "allies.empty" : "allies.disabled") : "allies.server"));
        list.setRows(new ArrayList<>(relations.keySet()));
        if (selectedTeam != null && relations.containsKey(selectedTeam)) {
            list.select(selectedTeam);
        }

        int third = (innerWidth() - 8) / 3;
        TextButton add = addRenderableWidget(new TextButton(left(), buttonsY, third, 20, t("allies.add"), button -> openPopup(new TeamPickerPopup(mine))));
        add.active = officer && view.info().alliesEnabled();
        if (!add.active) {
            add.withTooltip(t(mine.type() == TeamType.PARTY ? "allies.add.officers" : "allies.server"));
        }
        TextButton primary = addRenderableWidget(new TextButton(left() + third + 4, buttonsY, third, 20, Component.empty(), button -> {
            if (selectedTeam != null) {
                primaryAction(relations.get(selectedTeam), selectedTeam);
            }
        }));
        TextButton secondary = addRenderableWidget(new TextButton(left() + (third + 4) * 2, buttonsY, innerWidth() - (third + 4) * 2, 20,
                t("allies.decline"), button -> {
                    if (selectedTeam != null) {
                        request(Action.ALLY_DECLINE, selectedTeam);
                    }
                }));
        Runnable update = () -> {
            Relation relation = selectedTeam != null ? relations.get(selectedTeam) : null;
            primary.visible = relation != null;
            primary.active = officer;
            if (relation != null) {
                primary.setMessage(t("allies.action." + relation.name().toLowerCase(Locale.ROOT)));
            }
            secondary.visible = relation == Relation.REQUEST_IN;
            secondary.active = officer;
        };
        list.onSelect(id -> {
            selectedTeam = id;
            update.run();
        });
        list.onContextMenu((id, x, y) -> {
            Relation relation = relations.get(id);
            ContextMenu.Builder menu = ContextMenu.builder();
            if (!officer) {
                menu.addDisabled(t("allies.add.officers"), Icon.NONE);
            } else {
                menu.add(t("allies.action." + relation.name().toLowerCase(Locale.ROOT)), () -> primaryAction(relation, id));
                if (relation == Relation.REQUEST_IN) {
                    menu.addDanger(t("allies.decline"), Icon.NONE, () -> request(Action.ALLY_DECLINE, id));
                }
            }
            openPopup(menu.build(x, y));
        });
        update.run();
    }

    private static void primaryAction(Relation relation, UUID team) {
        request(switch (relation) {
            case REQUEST_IN -> Action.ALLY_ACCEPT;
            case ALLY, REQUEST_OUT -> Action.ALLY_REMOVE;
            case ENEMY -> Action.ENEMY_REMOVE;
        }, team);
    }

    // -- Settings --

    private String current(TeamViews.Details mine, TeamSetting setting) {
        TeamViews.Summary summary = mine.summary();
        return switch (setting) {
            case NAME -> summary.name();
            case TAG -> summary.tag();
            case COLOR -> TeamColors.hex(summary.color());
            case DESCRIPTION -> mine.description();
            case ICON -> summary.icon().map(Object::toString).orElse("none");
            case FREE_TO_JOIN -> Boolean.toString(summary.freeToJoin());
            case MAX_MEMBERS -> Integer.toString(mine.maxMembers());
            case ALLOW_ALLY_INVITES -> Boolean.toString(summary.allowAllyInvites());
            case FRIENDLY_FIRE -> Boolean.toString(mine.friendlyFire());
            case TEAM_CHAT_DEFAULT -> Boolean.toString(mine.teamChatDefault());
            case LOCKED -> Boolean.toString(mine.locked());
        };
    }

    private String value(TeamViews.Details mine, TeamSetting setting) {
        return draft.getOrDefault(setting, current(mine, setting));
    }

    // Records an edit (or forgets it, if it's back to the saved value) and whether it's valid.
    private void edit(TeamViews.Details mine, TeamSetting setting, String value, boolean valid) {
        if (value.equals(current(mine, setting))) {
            draft.remove(setting);
            invalid.remove(setting);
        } else {
            draft.put(setting, value);
            invalid.put(setting, !valid);
        }
        if (saveButton != null) {
            saveButton.active = canSave();
        }
    }

    private boolean canSave() {
        return !draft.isEmpty() && !invalid.containsValue(true) && !saving;
    }

    private void initSettings(TeamViews.PlayerView view, int top) {
        TeamViews.Details mine = view.mine();
        if (!mine.summary().id().equals(draftTeam)) {
            draft.clear();
            invalid.clear();
            draftTeam = mine.summary().id();
        }
        int buttonsY = bottom() - 20;
        ScrollPanel content = addRenderableWidget(new ScrollPanel(left(), top, innerWidth(), buttonsY - 4 - top, Panel.Style.INSET));
        int width = content.contentWidth();
        // Wide enough for the longest label shown, but never more than half the row.
        int labelWidth = Math.min(width / 2, TeamSetting.forType(mine.type()).stream()
                .mapToInt(setting -> font.width(t("settings." + setting.key()))).max().orElse(60) + 6);
        int fieldX = labelWidth + 4;
        int fieldWidth = width - fieldX;
        int y = 0;
        boolean editable = mine.canEdit();

        if (!editable) {
            content.add(new Label(width, t(mine.type() == TeamType.SERVER ? "settings.read_only.server" : "settings.read_only"), true), 0, y);
            y += 14;
            for (TeamSetting setting : TeamSetting.forType(mine.type())) {
                content.add(new Label(labelWidth, t("settings." + setting.key()), true), 0, y);
                content.add(new Label(fieldWidth, readOnlyValue(mine, setting), false), fieldX, y);
                y += 12;
            }
        } else {
            y = textField(content, mine, TeamSetting.NAME, y, labelWidth, fieldWidth, view.info().nameMaxLength(),
                    text -> CreatePartyPopup.nameValid(text, mine.summary().id()));
            y = textField(content, mine, TeamSetting.TAG, y, labelWidth, 60, TeamSetting.TAG_MAX_LENGTH, text -> TAG.matcher(text).matches());
            y = textField(content, mine, TeamSetting.DESCRIPTION, y, labelWidth, fieldWidth, TeamSetting.DESCRIPTION_MAX_LENGTH,
                    text -> text.length() <= TeamSetting.DESCRIPTION_MAX_LENGTH);
            int cap = view.info().maxMembersCap() > 0 ? view.info().maxMembersCap() : TeamSetting.MAX_MEMBERS_LIMIT;
            y = textField(content, mine, TeamSetting.MAX_MEMBERS, y, labelWidth, 50, 4,
                    text -> text.matches("\\d{1,4}") && Integer.parseInt(text) <= cap);

            content.add(new Label(labelWidth, t("settings.color"), true), 0, y + 5);
            int color = TeamColorsParse.parse(value(mine, TeamSetting.COLOR), mine.summary().color());
            content.add(new ColorPicker(0, 0, color, rgb -> edit(mine, TeamSetting.COLOR, TeamColors.hex(rgb), true)), fieldX, y);
            y += ColorPicker.HEIGHT + 4;

            content.add(new Label(labelWidth, t("settings.icon"), true), 0, y + 5);
            content.add(new ItemSlotWidget(0, 0, () -> TeamIcons.stack(iconValue(mine)).orElse(ItemStack.EMPTY))
                    .onClick(event -> openPopup(new ItemPickerPopup(picked -> {
                        edit(mine, TeamSetting.ICON, picked.map(Object::toString).orElse("none"), true);
                        rebuildWidgets();
                    }))).tooltip(t("settings.icon.tooltip")), fieldX, y);
            content.add(new TextButton(0, 0, 50, 18, t("settings.icon.clear"), button -> {
                edit(mine, TeamSetting.ICON, "none", true);
                rebuildWidgets();
            }), fieldX + ItemSlotWidget.SIZE + 4, y);
            y += ItemSlotWidget.SIZE + 4;

            List<TeamSetting> toggles = new ArrayList<>(List.of(TeamSetting.FREE_TO_JOIN, TeamSetting.ALLOW_ALLY_INVITES,
                    TeamSetting.FRIENDLY_FIRE, TeamSetting.TEAM_CHAT_DEFAULT, TeamSetting.LOCKED));
            toggles.removeIf(setting -> !setting.appliesTo(mine.type()));
            for (TeamSetting setting : toggles) {
                boolean on = Boolean.parseBoolean(value(mine, setting));
                ToggleButton toggle = content.add(new ToggleButton(0, 0, width, t("settings." + setting.key()), on,
                        value -> edit(mine, setting, Boolean.toString(value), true)), 0, y);
                if (setting == TeamSetting.FREE_TO_JOIN && !view.info().allowFreeToJoin() && !on) {
                    toggle.active = false;
                    toggle.withTooltip(t("settings.free_to_join.disabled"));
                }
                y += 18;
            }
        }

        // Danger zone: leaving, and disbanding for the owner (or an admin, for a server team).
        y += 6;
        TextLayoutWidget heading = content.add(new TextLayoutWidget(0, 0, width, "## " + t("settings.danger").getString()), 0, y);
        y += heading.getHeight() + 3;
        int half = (width - 4) / 2;
        boolean owner = mine.myRank() == TeamRank.OWNER;
        TextButton leave = content.add(new TextButton(half, t("settings.leave"), button -> confirmLeave(mine)), 0, y);
        leave.active = !(mine.type() == TeamType.SERVER && mine.locked());
        if (!leave.active) {
            leave.withTooltip(t("settings.leave.locked"));
        }
        boolean canDisband = mine.type() == TeamType.PARTY ? owner : mine.canEdit();
        TextButton disband = content.add(new TextButton(half, t("settings.disband"), button -> confirmDisband(mine)), half + 4, y);
        disband.active = canDisband;
        if (!canDisband) {
            disband.withTooltip(t(mine.type() == TeamType.PARTY ? "settings.disband.owner" : "settings.disband.admin"));
        }

        if (editable) {
            int buttonWidth = (innerWidth() - 4) / 2;
            saveButton = addRenderableWidget(new TextButton(left(), buttonsY, buttonWidth, 20, t("settings.save"), button -> save(mine)).accent());
            saveButton.active = canSave();
            TextButton revert = addRenderableWidget(new TextButton(left() + buttonWidth + 4, buttonsY, buttonWidth, 20, t("settings.revert"), button -> {
                draft.clear();
                invalid.clear();
                rebuildWidgets();
            }));
            revert.active = !draft.isEmpty();
        }
    }

    // A labelled one-line field for a text setting; returns the y below it.
    private int textField(ScrollPanel content, TeamViews.Details mine, TeamSetting setting, int y, int labelWidth, int width,
            int maxLength, java.util.function.Predicate<String> valid) {
        content.add(new Label(labelWidth, t("settings." + setting.key()), true), 0, y + 5);
        TextInput input = content.add(new TextInput(width, t("settings." + setting.key())), labelWidth + 4, y);
        input.setMaxLength(maxLength);
        input.setValue(value(mine, setting));
        input.moveCursorToStart(false);
        input.validator(valid);
        input.setResponder(text -> edit(mine, setting, text.strip(), valid.test(text)));
        return y + 18 + 4;
    }

    private Optional<net.minecraft.resources.Identifier> iconValue(TeamViews.Details mine) {
        String value = value(mine, TeamSetting.ICON);
        return value.equals("none") ? Optional.empty() : Optional.ofNullable(net.minecraft.resources.Identifier.tryParse(value));
    }

    private Component readOnlyValue(TeamViews.Details mine, TeamSetting setting) {
        String value = current(mine, setting);
        return switch (setting) {
            case FREE_TO_JOIN, ALLOW_ALLY_INVITES, FRIENDLY_FIRE, TEAM_CHAT_DEFAULT, LOCKED ->
                    Boolean.parseBoolean(value) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
            case MAX_MEMBERS -> value.equals("0") ? t("settings.no_limit") : Component.literal(value);
            case ICON -> TeamIcons.stack(mine.summary().icon()).map(ItemStack::getHoverName).orElse(t("settings.no_icon"));
            case DESCRIPTION -> value.isEmpty() ? t("settings.no_description") : Component.literal(value);
            default -> Component.literal(value);
        };
    }

    private void save(TeamViews.Details mine) {
        if (!canSave()) {
            return;
        }
        List<TeamPayloads.Setting> settings = new ArrayList<>();
        draft.forEach((setting, value) -> settings.add(new TeamPayloads.Setting(setting.key(), value)));
        saving = true;
        if (saveButton != null) {
            saveButton.active = false;
        }
        send(new TeamPayloads.Settings(mine.summary().id(), settings));
    }

    private void confirmLeave(TeamViews.Details mine) {
        String name = mine.summary().name();
        if (mine.type() == TeamType.PARTY && mine.myRank() == TeamRank.OWNER) {
            // The owner can't leave: say why, and offer the way out.
            openPopup(new ConfirmDialog(t("confirm.owner_leave.title"), t("confirm.owner_leave.message"),
                    t("confirm.owner_leave.members"), CommonComponents.GUI_CANCEL, yes -> {
                        if (yes) {
                            showTab(MEMBERS);
                        }
                    }));
            return;
        }
        openPopup(new ConfirmDialog(t("confirm.leave.title", name), t("confirm.leave.message"), t("confirm.leave.confirm"),
                CommonComponents.GUI_CANCEL, yes -> {
                    if (yes) {
                        send(TeamPayloads.Request.of(Action.LEAVE));
                    }
                }));
    }

    private void confirmDisband(TeamViews.Details mine) {
        String name = mine.summary().name();
        openPopup(new ConfirmDialog(t("confirm.disband.title", name), t("confirm.disband.message"), t("confirm.disband.confirm"),
                CommonComponents.GUI_CANCEL, yes -> {
                    if (yes) {
                        request(Action.DISBAND, mine.summary().id());
                    }
                }).dangerous().requireTyping(name));
    }

    // ---- Drawing ----

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        HibiscusTheme theme = HibiscusTheme.current();
        Optional<TeamViews.PlayerView> view = ClientTeamState.view();
        if (view.isEmpty() || view.get().mine().type().isPlayer()) {
            super.extractPanel(graphics, mouseX, mouseY, a);
            return;
        }
        TeamViews.Details mine = view.get().mine();
        theme.panel(graphics, panelX, panelY, panelWidth, panelHeight);
        TeamIcons.team(mine.summary()).draw(graphics, panelX + 7, panelY + 6);
        int textX = panelX + 28;
        int right = panelX + panelWidth - 8;
        Component side = statusShowing() ? Component.empty() : rankName(mine.myRank());
        int sideWidth = font.width(side);
        graphics.text(font, TeamIcons.fit(font, TeamIcons.name(mine.summary()), right - textX - sideWidth - 6), textX, panelY + 6, theme.text(), false);
        graphics.text(font, side, right - sideWidth, panelY + 6, theme.textMuted(), false);
        Component line = mine.description().isEmpty()
                ? (mine.type() == TeamType.SERVER ? t("header.server_team", mine.members().size()) : t("header.party", mine.members().size()))
                : Component.literal(mine.description());
        graphics.text(font, TeamIcons.fit(font, line, right - textX), textX, panelY + 17, theme.textMuted(), false);
    }

    private boolean statusShowing() {
        return Util.getMillis() < statusUntil && !status.getString().isEmpty();
    }

    @Override
    protected void extractForeground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        HibiscusTheme theme = HibiscusTheme.current();
        Optional<TeamViews.PlayerView> view = ClientTeamState.view();
        int x = left() + 2;
        if (view.isEmpty()) {
            graphics.centeredText(font, t("waiting"), panelX + panelWidth / 2, panelY + panelHeight / 2 - 4, theme.textMuted());
        } else if (view.get().mine().type().isPlayer()) {
            int y = panelY + HEADER;
            graphics.text(font, t("solo.title"), x, y, theme.text(), false);
            graphics.text(font, TeamIcons.fit(font, t("solo.subtitle", view.get().mine().summary().name()), innerWidth() - 4), x, y + 11, theme.textMuted(), false);
            graphics.text(font, t("solo.list"), x, y + 24 + 20 + 3, theme.textMuted(), false);
        }
        if (statusShowing()) {
            int color = theme.color(statusOk ? HibiscusTheme.POSITIVE : HibiscusTheme.NEGATIVE);
            boolean teamHeader = view.isPresent() && !view.get().mine().type().isPlayer();
            int room = teamHeader ? panelWidth / 2 - 8 : panelWidth - 24 - font.width(title);
            int width = Math.min(font.width(status), room);
            graphics.text(font, TeamIcons.fit(font, status, room), panelX + panelWidth - 8 - width, panelY + (teamHeader ? 6 : 7), color, false);
        }
    }

    // A "#RRGGBB" string to a colour, else the fallback.
    private static final class TeamColorsParse {
        static int parse(String text, int fallback) {
            try {
                return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
