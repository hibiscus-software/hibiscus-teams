/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.network.HibiscusNetwork;
import software.hibiscus.hibiscuslib.util.PermissionLevels;
import software.hibiscus.hibiscuslib.util.Profiles;
import software.hibiscus.hibiscusteams.command.TeamPermissions;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamMessages;
import software.hibiscus.hibiscusteams.team.TeamOps;

// Keeps clients' copies of the teams current. Any change marks the sync dirty; at the end of that tick the summaries
// and each online player's view are worked out again and only what differs from what they last got is sent. Also
// routes TeamMessages notifications to clients as Notify payloads (chat and/or toast, the client decides).
public final class TeamSync {
    // Views are recomputed this often even without a change, to pick up op changes and lapsed invites.
    private static final int REFRESH_TICKS = 100;

    private static final Map<UUID, TeamViews.Summary> SENT_SUMMARIES = new LinkedHashMap<>();
    private static final Map<UUID, TeamViews.PlayerView> SENT_VIEWS = new HashMap<>();
    private static boolean dirty;
    private static long tick;

    private TeamSync() {}

    public static void register() {
        TeamManagerImpl.INSTANCE.addChangeListener(team -> dirty = true);
        TeamMessages.setSink((player, kind, message, toast) -> {
            if (player.connection.hasChannel(TeamPayloads.Notify.TYPE)) {
                HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Notify(kind, message, toast));
            } else {
                player.sendSystemMessage(message);
            }
        });
    }

    public static void markDirty() {
        dirty = true;
    }

    // A player joined: the whole list of teams, and their view.
    public static void onLogin(ServerPlayer player) {
        if (!player.connection.hasChannel(TeamPayloads.Summaries.TYPE)) {
            return;
        }
        HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Summaries(true, new ArrayList<>(summaries().values()), List.of()));
        TeamViews.PlayerView view = viewFor(player);
        SENT_VIEWS.put(player.getUUID(), view);
        HibiscusNetwork.sendToPlayer(player, new TeamPayloads.View(view));
        // Their teammates see them come online.
        dirty = true;
    }

    public static void onLogout(ServerPlayer player) {
        SENT_VIEWS.remove(player.getUUID());
        dirty = true;
    }

    public static void onServerStop() {
        SENT_SUMMARIES.clear();
        SENT_VIEWS.clear();
        dirty = false;
    }

    // At the end of each server tick: send what changed.
    public static void tick(MinecraftServer server) {
        if (!TeamManagerImpl.INSTANCE.isRunning()) {
            return;
        }
        if (!dirty && ++tick % REFRESH_TICKS != 0) {
            return;
        }
        dirty = false;
        Map<UUID, TeamViews.Summary> current = summaries();
        List<TeamViews.Summary> upserts = new ArrayList<>();
        current.forEach((id, summary) -> {
            if (!summary.equals(SENT_SUMMARIES.get(id))) {
                upserts.add(summary);
            }
        });
        List<UUID> removed = SENT_SUMMARIES.keySet().stream().filter(id -> !current.containsKey(id)).toList();
        if (!upserts.isEmpty() || !removed.isEmpty()) {
            TeamPayloads.Summaries payload = new TeamPayloads.Summaries(false, upserts, removed);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.connection.hasChannel(TeamPayloads.Summaries.TYPE)) {
                    HibiscusNetwork.sendToPlayer(player, payload);
                }
            }
        }
        SENT_SUMMARIES.clear();
        SENT_SUMMARIES.putAll(current);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.connection.hasChannel(TeamPayloads.View.TYPE)) {
                continue;
            }
            TeamViews.PlayerView view = viewFor(player);
            if (!view.equals(SENT_VIEWS.get(player.getUUID()))) {
                SENT_VIEWS.put(player.getUUID(), view);
                HibiscusNetwork.sendToPlayer(player, new TeamPayloads.View(view));
            }
        }
    }

    private static Map<UUID, TeamViews.Summary> summaries() {
        Map<UUID, TeamViews.Summary> summaries = new LinkedHashMap<>();
        for (TeamData team : TeamManagerImpl.INSTANCE.groups()) {
            summaries.put(team.id(), summary(team));
        }
        return summaries;
    }

    static TeamViews.Summary summary(TeamData team) {
        return new TeamViews.Summary(team.id(), team.type(), team.name(), team.shortName(), team.color(), team.icon(),
                team.members().size(), team.freeToJoin(), team.allowAllyInvites());
    }

    // What one player sees: their team in full (invites only for those who can manage them), their invites, and the
    // server settings.
    public static TeamViews.PlayerView viewFor(ServerPlayer player) {
        TeamManagerImpl teams = TeamManagerImpl.INSTANCE;
        MinecraftServer server = player.level().getServer();
        UUID self = player.getUUID();
        long now = System.currentTimeMillis();
        TeamData team = teams.teamOf(self);
        TeamRank rank = team.memberRank(self);
        boolean canEdit = TeamOps.canEdit(TeamNetwork.actorFor(player, team), team);

        List<TeamViews.Member> members = new ArrayList<>();
        team.members().forEach((id, memberRank) -> members.add(new TeamViews.Member(id, name(server, id), memberRank,
                server.getPlayerList().getPlayer(id) != null)));
        List<TeamViews.Pending> invited = new ArrayList<>();
        if (rank.isAtLeast(TeamRank.OFFICER) || canEdit) {
            team.invites().forEach((id, invite) -> {
                if (invite.expires() > now) {
                    invited.add(new TeamViews.Pending(id, name(server, id), invite.expires()));
                }
            });
        }
        List<UUID> requestsIn = new ArrayList<>();
        team.allyRequests().forEach((from, expires) -> {
            if (expires > now && teams.teamData(from).isPresent()) {
                requestsIn.add(from);
            }
        });
        List<UUID> requestsOut = teams.groups().stream().filter(other -> other.hasAllyRequest(team.id(), now)).map(TeamData::id).toList();
        TeamViews.Details details = new TeamViews.Details(summary(team), team.description(), members, invited,
                TeamViews.copy(team.allies()), TeamViews.copy(team.enemies()), requestsIn, new ArrayList<>(requestsOut),
                team.maxMembers(), TeamOps.memberLimit(team), team.friendlyFire(), team.teamChatDefault(), team.locked(),
                rank, canEdit, TeamOps.teamChatByDefault(self));

        List<TeamViews.Pending> invites = new ArrayList<>();
        for (TeamData inviting : TeamOps.invitesFor(self)) {
            TeamData.Invite invite = inviting.invites().get(self);
            String by = invite.by() != null ? name(server, invite.by()) : "";
            invites.add(new TeamViews.Pending(inviting.id(), by, invite.expires()));
        }
        TeamViews.ServerInfo info = new TeamViews.ServerInfo(TeamsConfig.PARTIES_ENABLED.getAsBoolean(),
                TeamsConfig.ALLIES_ENABLED.getAsBoolean(), TeamsConfig.PARTIES_ALLOW_FREE_TO_JOIN.getAsBoolean(),
                TeamsConfig.PARTIES_NAME_MAX_LENGTH.getAsInt(), TeamsConfig.PARTIES_MAX_MEMBERS.getAsInt(),
                PermissionLevels.check(player, TeamPermissions.SERVER_TEAMS));
        return new TeamViews.PlayerView(details, invites, info);
    }

    private static String name(MinecraftServer server, UUID player) {
        return Profiles.nameOf(server, player).orElse(player.toString().substring(0, 8));
    }
}
