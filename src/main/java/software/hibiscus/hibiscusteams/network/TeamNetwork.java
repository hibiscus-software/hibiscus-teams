/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.network;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.network.HibiscusNetwork;
import software.hibiscus.hibiscuslib.util.PermissionLevels;
import software.hibiscus.hibiscuslib.util.Profiles;
import software.hibiscus.hibiscuslib.util.RateLimiter;
import software.hibiscus.hibiscusteams.HibiscusTeams;
import software.hibiscus.hibiscusteams.command.TeamPermissions;
import software.hibiscus.hibiscusteams.network.TeamPayloads.Action;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamException;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// The Hibiscus Teams channel, and the server's handling of GUI requests. Each request is rate limited and then goes
// through TeamOps exactly as the matching command would, so the checks live in one place; the player gets a Result
// back, and the change reaches every affected client through TeamSync.
public final class TeamNetwork {
    // Bumped whenever a payload changes, so mismatched clients and servers refuse each other.
    private static final String VERSION = "1";
    private static final RateLimiter REQUESTS = new RateLimiter(10, 4.0);

    private TeamNetwork() {}

    public static void register(IEventBus modEventBus) {
        HibiscusNetwork.channel(modEventBus, VERSION, channel -> channel
                .toClient(TeamPayloads.Summaries.TYPE, TeamPayloads.Summaries.STREAM_CODEC, (payload, context) -> ClientBridge.summaries(payload))
                .toClient(TeamPayloads.View.TYPE, TeamPayloads.View.STREAM_CODEC, (payload, context) -> ClientBridge.view(payload))
                .toClient(TeamPayloads.Notify.TYPE, TeamPayloads.Notify.STREAM_CODEC, (payload, context) -> ClientBridge.notify(payload))
                .toClient(TeamPayloads.Result.TYPE, TeamPayloads.Result.STREAM_CODEC, (payload, context) -> ClientBridge.result(payload))
                .toServer(TeamPayloads.Request.TYPE, TeamPayloads.Request.STREAM_CODEC, TeamNetwork::request)
                .toServer(TeamPayloads.CreateParty.TYPE, TeamPayloads.CreateParty.STREAM_CODEC, TeamNetwork::createParty)
                .toServer(TeamPayloads.Settings.TYPE, TeamPayloads.Settings.STREAM_CODEC, TeamNetwork::settings));
    }

    public static void forget(UUID player) {
        REQUESTS.forget(player);
    }

    // Runs a request for a player and tells them how it went.
    private static void answer(IPayloadContext context, Handler handler) {
        if (!(context.player() instanceof ServerPlayer player) || !TeamManagerImpl.INSTANCE.isRunning()) {
            return;
        }
        if (!REQUESTS.tryAcquire(player.getUUID())) {
            HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Result(false, Component.translatable("hibiscusteams.error.slow_down")));
            return;
        }
        try {
            Component message = handler.run(player);
            HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Result(true, message));
        } catch (TeamException e) {
            HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Result(false, e.reason()));
        } catch (RuntimeException e) {
            HibiscusTeams.LOGGER.error("Team request from {} failed", player.nameAndId().name(), e);
            HibiscusNetwork.sendToPlayer(player, new TeamPayloads.Result(false, Component.translatable("hibiscusteams.error.request_failed")));
        }
    }

    @FunctionalInterface
    private interface Handler {
        Component run(ServerPlayer player);
    }

    // Who the player acts as on a team: an admin for server teams if they hold the node, else themselves.
    static Actor actorFor(ServerPlayer player, TeamData team) {
        if (team.type() == TeamType.SERVER && PermissionLevels.check(player, TeamPermissions.SERVER_TEAMS)) {
            return Actor.admin(player.getUUID());
        }
        return Actor.player(player.getUUID());
    }

    private static TeamData team(TeamPayloads.Request request) {
        UUID id = request.target().orElseThrow(() -> TeamException.of("unknown_team", "?"));
        return TeamManagerImpl.INSTANCE.teamData(id).filter(team -> !team.type().isPlayer())
                .orElseThrow(() -> TeamException.of("unknown_team", id.toString().substring(0, 8)));
    }

    private static UUID player(TeamPayloads.Request request) {
        return request.target().orElseThrow(() -> TeamException.of("unknown_player", "?"));
    }

    private static void request(TeamPayloads.Request request, IPayloadContext context) {
        answer(context, player -> {
            UUID self = player.getUUID();
            Actor actor = Actor.player(self);
            return switch (request.action()) {
                case INVITE -> TeamOps.invite(actor, player(request));
                case INVITE_NAME -> {
                    String name = request.text().strip();
                    UUID target = Profiles.idOrOffline(player.level().getServer(), name)
                            .orElseThrow(() -> TeamException.of("unknown_player", name));
                    yield TeamOps.invite(actor, target);
                }
                case ACCEPT -> TeamOps.accept(self, team(request));
                case DECLINE -> TeamOps.decline(self, team(request));
                case JOIN -> TeamOps.joinParty(self, team(request));
                case LEAVE -> TeamOps.leave(self);
                case KICK -> TeamOps.kick(actor, player(request));
                case PROMOTE -> TeamOps.promote(actor, player(request));
                case DEMOTE -> TeamOps.demote(actor, player(request));
                case TRANSFER -> TeamOps.transferOwnership(actor, player(request));
                case DISBAND -> {
                    TeamData team = team(request);
                    yield TeamOps.disband(actorFor(player, team), team);
                }
                case ALLY_REQUEST -> TeamOps.requestAlly(actor, team(request));
                case ALLY_ACCEPT -> TeamOps.acceptAlly(actor, team(request));
                case ALLY_DECLINE -> TeamOps.declineAlly(actor, team(request));
                case ALLY_REMOVE -> TeamOps.removeAlly(actor, team(request));
                case ENEMY_ADD -> TeamOps.addEnemy(actor, team(request));
                case ENEMY_REMOVE -> TeamOps.removeEnemy(actor, team(request));
                case TEAM_CHAT -> Component.translatable(TeamOps.toggleTeamChat(self)
                        ? "hibiscusteams.message.team_chat_on" : "hibiscusteams.message.team_chat_off");
            };
        });
    }

    // Creates the party, then applies the dialog's other choices; a bad colour or icon doesn't undo the party.
    private static void createParty(TeamPayloads.CreateParty request, IPayloadContext context) {
        answer(context, player -> {
            TeamData party = TeamOps.createParty(player.getUUID(), request.name());
            Actor actor = Actor.player(player.getUUID());
            TeamOps.set(actor, party, TeamSetting.COLOR, String.format(java.util.Locale.ROOT, "#%06X", request.color() & 0xFFFFFF));
            request.icon().ifPresent(icon -> TeamOps.set(actor, party, TeamSetting.ICON, icon.toString()));
            if (request.freeToJoin()) {
                TeamOps.set(actor, party, TeamSetting.FREE_TO_JOIN, "true");
            }
            return Component.translatable("hibiscusteams.message.created", party.displayName());
        });
    }

    // Applies each changed setting in turn; the first one that's refused stops the rest and says why.
    private static void settings(TeamPayloads.Settings request, IPayloadContext context) {
        answer(context, player -> {
            TeamData team = TeamManagerImpl.INSTANCE.teamData(request.team()).filter(found -> !found.type().isPlayer())
                    .orElseThrow(() -> TeamException.of("unknown_team", "?"));
            Actor actor = actorFor(player, team);
            for (TeamPayloads.Setting setting : request.settings()) {
                TeamSetting key = TeamSetting.byKey(setting.key()).orElseThrow(() -> TeamException.of("unknown_setting", setting.key()));
                TeamOps.set(actor, team, key, setting.value());
            }
            return Component.translatable("hibiscusteams.message.settings_saved");
        });
    }
}
