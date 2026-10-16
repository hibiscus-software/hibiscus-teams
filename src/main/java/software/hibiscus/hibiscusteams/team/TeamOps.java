/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import software.hibiscus.hibiscuslib.api.team.Team;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.api.team.event.PlayerJoinTeamRequestEvent;
import software.hibiscus.hibiscuslib.api.team.event.PlayerJoinedTeamEvent;
import software.hibiscus.hibiscuslib.api.team.event.PlayerLeftTeamEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamAlliesChangedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamCreatedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamDeletedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamPropertiesChangedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamPropertiesChangedEvent.Property;
import software.hibiscus.hibiscuslib.util.RateLimiter;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.team.TeamMessages.Kind;

// Every change to teams, for commands and GUI requests alike: checks who may do it (rank, admin, config, rate),
// changes the team, posts the Teams API events in the order Hibiscus Lib documents, notifies the players involved,
// and returns a message for whoever asked. A refused change throws TeamException before anything is touched.
// Server thread only.
public final class TeamOps {
    // Invites and ally requests message other players, so they're limited per sender.
    private static final RateLimiter INVITES = new RateLimiter(6, 0.5);
    private static final RateLimiter ALLY_REQUESTS = new RateLimiter(4, 0.2);

    private TeamOps() {}

    // Drops a player's rate-limit buckets when they log out.
    public static void forget(UUID player) {
        INVITES.forget(player);
        ALLY_REQUESTS.forget(player);
    }

    private static TeamManagerImpl teams() {
        return TeamManagerImpl.INSTANCE;
    }

    private static MinecraftServer server() {
        return teams().server();
    }

    private static @Nullable ServerPlayer online(UUID player) {
        return server().getPlayerList().getPlayer(player);
    }

    private static Component name(UUID player) {
        return TeamMessages.player(server(), player);
    }

    private static MutableComponent msg(String key, Object... args) {
        return Component.translatable("hibiscusteams.message." + key, args);
    }

    // When an invite or ally request made now lapses.
    private static long expiresAt() {
        long lifetime = TeamsConfig.inviteLifetimeMillis();
        long now = System.currentTimeMillis();
        return lifetime >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + lifetime;
    }

    // ---- Checks ----

    // A valid, unused party or server team name (ignoring case, and ignoring the team being renamed).
    public static String checkName(String raw, @Nullable TeamData self) {
        String name = raw.strip();
        int max = TeamsConfig.PARTIES_NAME_MAX_LENGTH.getAsInt();
        if (name.isEmpty() || name.length() > max || name.indexOf('§') >= 0 || name.chars().anyMatch(Character::isISOControl)) {
            throw TeamException.of("invalid_name", max);
        }
        Optional<TeamData> existing = teams().byName(name);
        if (existing.isPresent() && !existing.get().equals(self)) {
            throw TeamException.of("name_taken", name);
        }
        return name;
    }

    // The party a player is in, for party actions; server teams are run by admins, so they don't count.
    private static TeamData requireParty(UUID player) {
        TeamData team = teams().teamOf(player);
        if (team.type() == TeamType.SERVER) {
            throw TeamException.of("server_team_managed");
        }
        if (team.type() != TeamType.PARTY) {
            throw TeamException.of("not_in_party");
        }
        return team;
    }

    private static void requireRank(TeamData team, UUID player, TeamRank rank) {
        if (!team.memberRank(player).isAtLeast(rank)) {
            throw TeamException.of(rank == TeamRank.OWNER ? "owner_only" : "officer_only");
        }
    }

    // Whether the actor may change a team's settings: admins any team, owners and officers their own party.
    public static boolean canEdit(Actor actor, TeamData team) {
        if (team.type().isPlayer()) {
            return false;
        }
        if (actor.admin()) {
            return true;
        }
        return team.type() == TeamType.PARTY && actor.player() != null && team.memberRank(actor.player()).isAtLeast(TeamRank.OFFICER);
    }

    // The member limit in force: the party's own, capped by the server's (0 = none).
    public static int memberLimit(TeamData team) {
        if (team.type() != TeamType.PARTY) {
            return 0;
        }
        int cap = TeamsConfig.PARTIES_MAX_MEMBERS.getAsInt();
        int own = team.maxMembers();
        if (own == 0 || cap == 0) {
            return Math.max(own, cap);
        }
        return Math.min(own, cap);
    }

    private static void checkRoom(TeamData team) {
        int limit = memberLimit(team);
        if (limit > 0 && team.members().size() >= limit) {
            throw TeamException.of("team_full", TeamMessages.team(team), limit);
        }
    }

    // Asks other mods whether the player may join (PlayerJoinTeamRequestEvent); throws their reason if not.
    private static void requestJoin(TeamData team, UUID player, Team from) {
        PlayerJoinTeamRequestEvent event = NeoForge.EVENT_BUS.post(new PlayerJoinTeamRequestEvent(team, player, online(player), from));
        if (event.isCanceled()) {
            throw new TeamException(event.denyReason().orElseGet(() -> Component.translatable("hibiscusteams.error.join_denied")));
        }
    }

    // ---- Creating ----

    // A new party owned by its creator, who moves into it from their player team.
    public static TeamData createParty(UUID creator, String rawName) {
        if (!TeamsConfig.PARTIES_ENABLED.getAsBoolean()) {
            throw TeamException.of("parties_disabled");
        }
        TeamData current = teams().teamOf(creator);
        if (!current.type().isPlayer()) {
            throw TeamException.of("leave_first", TeamMessages.team(current));
        }
        TeamData party = newGroup(TeamType.PARTY, checkName(rawName, null));
        // Asked before the party exists, so a refusal leaves nothing behind (TeamsApi.get().team(id) is still empty).
        requestJoin(party, creator, current);
        party.putMember(creator, TeamRank.OWNER);
        teams().register(party);
        teams().changed(party);
        NeoForge.EVENT_BUS.post(new TeamCreatedEvent(party, creator));
        NeoForge.EVENT_BUS.post(new PlayerJoinedTeamEvent(creator, online(creator), current, party));
        return party;
    }

    // A server team: no owner, members added by admins.
    public static TeamData createServerTeam(@Nullable UUID creator, String rawName) {
        TeamData team = newGroup(TeamType.SERVER, checkName(rawName, null));
        teams().register(team);
        teams().changed(team);
        NeoForge.EVENT_BUS.post(new TeamCreatedEvent(team, creator));
        return team;
    }

    private static TeamData newGroup(TeamType type, String name) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (teams().teamData(id).isPresent());
        TeamData team = new TeamData(id, type, name, TeamSetting.defaultTag(name), TeamColors.forId(id));
        team.setCreated(System.currentTimeMillis());
        return team;
    }

    // ---- Invites and joining ----

    public static Component invite(Actor actor, UUID target) {
        UUID inviter = actor.requirePlayer();
        TeamData party = requireParty(inviter);
        requireRank(party, inviter, TeamRank.OFFICER);
        if (party.isMember(target)) {
            throw TeamException.of("already_member", name(target), TeamMessages.team(party));
        }
        checkRoom(party);
        if (!INVITES.tryAcquire(inviter)) {
            throw TeamException.of("slow_down");
        }
        party.putInvite(target, new TeamData.Invite(inviter, expiresAt()));
        teams().changed(party);

        String quoted = TeamMessages.quote(party.name());
        Component toast = msg("invited", name(inviter), TeamMessages.team(party));
        Component invite = msg("invited", name(inviter), TeamMessages.team(party))
                .append(" ")
                .append(TeamMessages.button("hibiscusteams.button.accept", ChatFormatting.GREEN, TeamMessages.command("party accept " + quoted), null))
                .append(" ")
                .append(TeamMessages.button("hibiscusteams.button.decline", ChatFormatting.RED, TeamMessages.command("party decline " + quoted), null));
        TeamMessages.send(server(), target, Kind.INVITED, invite, toast);
        TeamMessages.toOfficers(server(), party, Kind.INFO, msg("invite_sent_team", name(inviter), name(target)));
        return msg("invite_sent", name(target), TeamMessages.team(party));
    }

    // The parties and server teams that have invited a player and are still waiting.
    public static List<TeamData> invitesFor(UUID player) {
        long now = System.currentTimeMillis();
        List<TeamData> invites = new ArrayList<>();
        for (TeamData team : teams().groups()) {
            if (team.hasInvite(player, now)) {
                invites.add(team);
            }
        }
        return invites;
    }

    // Accepts an invite: to the given team, or the player's only one when it's null.
    public static Component accept(UUID player, @Nullable TeamData team) {
        TeamData target = team != null ? team : onlyInvite(player);
        if (!target.hasInvite(player, System.currentTimeMillis())) {
            throw TeamException.of("not_invited", TeamMessages.team(target));
        }
        return join(player, target);
    }

    public static Component decline(UUID player, @Nullable TeamData team) {
        TeamData target = team != null ? team : onlyInvite(player);
        if (!target.removeInvite(player)) {
            throw TeamException.of("not_invited", TeamMessages.team(target));
        }
        teams().changed(target);
        TeamMessages.toOfficers(server(), target, Kind.INFO, msg("invite_declined_team", name(player)));
        return msg("invite_declined", TeamMessages.team(target));
    }

    private static TeamData onlyInvite(UUID player) {
        List<TeamData> invites = invitesFor(player);
        if (invites.isEmpty()) {
            throw TeamException.of("no_invites");
        }
        if (invites.size() > 1) {
            throw TeamException.of("several_invites");
        }
        return invites.getFirst();
    }

    // Joins a party the player is invited to, or one that's free to join.
    public static Component joinParty(UUID player, TeamData team) {
        if (team.type() != TeamType.PARTY) {
            throw TeamException.of("server_team_managed");
        }
        if (team.hasInvite(player, System.currentTimeMillis())) {
            return join(player, team);
        }
        if (team.freeToJoin() && TeamsConfig.PARTIES_ALLOW_FREE_TO_JOIN.getAsBoolean()) {
            return join(player, team);
        }
        throw TeamException.of("not_invited", TeamMessages.team(team));
    }

    private static Component join(UUID player, TeamData team) {
        if (team.isMember(player)) {
            throw TeamException.of("already_in_team", TeamMessages.team(team));
        }
        TeamData current = teams().teamOf(player);
        if (!current.type().isPlayer()) {
            throw TeamException.of("leave_first", TeamMessages.team(current));
        }
        checkRoom(team);
        requestJoin(team, player, current);
        moveTo(player, team, TeamRank.MEMBER, PlayerLeftTeamEvent.Reason.SWITCHED);
        TeamMessages.toTeam(server(), team, Kind.JOINED, msg("joined_team", name(player), TeamMessages.team(team)), player);
        return msg("joined", TeamMessages.team(team));
    }

    // ---- Leaving and kicking ----

    public static Component leave(UUID player) {
        TeamData team = teams().teamOf(player);
        if (team.type().isPlayer()) {
            throw TeamException.of("not_in_party");
        }
        if (team.memberRank(player) == TeamRank.OWNER) {
            throw TeamException.of("owner_cannot_leave");
        }
        if (team.type() == TeamType.SERVER && team.locked()) {
            throw TeamException.of("team_locked", TeamMessages.team(team));
        }
        moveTo(player, teams().playerTeam(player), TeamRank.OWNER, PlayerLeftTeamEvent.Reason.LEFT);
        TeamMessages.toTeam(server(), team, Kind.LEFT, msg("left_team", name(player), TeamMessages.team(team)), player);
        return msg("left", TeamMessages.team(team));
    }

    // Removes a member ranked below the kicker, or withdraws an invite.
    public static Component kick(Actor actor, UUID target) {
        UUID kicker = actor.requirePlayer();
        TeamData party = requireParty(kicker);
        requireRank(party, kicker, TeamRank.OFFICER);
        if (target.equals(kicker)) {
            throw TeamException.of("cannot_kick_self");
        }
        if (!party.isMember(target)) {
            if (party.removeInvite(target)) {
                teams().changed(party);
                return msg("invite_withdrawn", name(target));
            }
            throw TeamException.of("not_member", name(target), TeamMessages.team(party));
        }
        if (party.memberRank(target).ordinal() >= party.memberRank(kicker).ordinal()) {
            throw TeamException.of("cannot_kick_rank", name(target));
        }
        moveTo(target, teams().playerTeam(target), TeamRank.OWNER, PlayerLeftTeamEvent.Reason.KICKED);
        TeamMessages.send(server(), target, Kind.KICKED, msg("kicked_you", TeamMessages.team(party), name(kicker)));
        TeamMessages.toTeam(server(), party, Kind.LEFT, msg("kicked_team", name(target), name(kicker)), kicker);
        return msg("kicked", name(target), TeamMessages.team(party));
    }

    // ---- Ranks ----

    public static Component promote(Actor actor, UUID target) {
        UUID owner = actor.requirePlayer();
        TeamData party = requireParty(owner);
        requireRank(party, owner, TeamRank.OWNER);
        TeamRank rank = party.memberRank(target);
        if (!rank.isMember()) {
            throw TeamException.of("not_member", name(target), TeamMessages.team(party));
        }
        if (rank != TeamRank.MEMBER) {
            throw TeamException.of("cannot_promote", name(target));
        }
        return setRank(party, target, TeamRank.OFFICER, "promoted");
    }

    public static Component demote(Actor actor, UUID target) {
        UUID owner = actor.requirePlayer();
        TeamData party = requireParty(owner);
        requireRank(party, owner, TeamRank.OWNER);
        TeamRank rank = party.memberRank(target);
        if (!rank.isMember()) {
            throw TeamException.of("not_member", name(target), TeamMessages.team(party));
        }
        if (rank != TeamRank.OFFICER) {
            throw TeamException.of("cannot_demote", name(target));
        }
        return setRank(party, target, TeamRank.MEMBER, "demoted");
    }

    private static Component setRank(TeamData party, UUID target, TeamRank rank, String key) {
        party.putMember(target, rank);
        teams().changed(party);
        NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(party, Set.of(Property.RANKS)));
        TeamMessages.send(server(), target, Kind.RANK, msg(key + "_you", TeamMessages.rank(rank), TeamMessages.team(party)));
        return msg(key, name(target), TeamMessages.rank(rank));
    }

    // The owner hands the party to another member and becomes an officer.
    public static Component transferOwnership(Actor actor, UUID target) {
        UUID owner = actor.requirePlayer();
        TeamData party = requireParty(owner);
        requireRank(party, owner, TeamRank.OWNER);
        if (target.equals(owner)) {
            throw TeamException.of("already_owner");
        }
        if (!party.isMember(target)) {
            throw TeamException.of("not_member", name(target), TeamMessages.team(party));
        }
        party.putMember(target, TeamRank.OWNER);
        party.putMember(owner, TeamRank.OFFICER);
        teams().changed(party);
        NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(party, Set.of(Property.OWNER, Property.RANKS)));
        TeamMessages.toTeam(server(), party, Kind.OWNERSHIP, msg("ownership_transferred", name(owner), name(target), TeamMessages.team(party)), owner);
        return msg("ownership_transferred", name(owner), name(target), TeamMessages.team(party));
    }

    // ---- Settings ----

    public static Component set(Actor actor, TeamData team, TeamSetting setting, String value) {
        if (!setting.appliesTo(team.type())) {
            throw TeamException.of("setting_not_applicable", setting.key());
        }
        if (!canEdit(actor, team)) {
            throw TeamException.of(team.type() == TeamType.SERVER ? "server_team_managed" : "officer_only");
        }
        if (setting.set(team, value)) {
            teams().changed(team);
            NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(team, EnumSet.of(setting.property())));
        }
        return msg("setting_set", setting.key(), setting.get(team));
    }

    // ---- Deleting ----

    public static Component disband(Actor actor, TeamData team) {
        if (team.type().isPlayer()) {
            throw TeamException.of("cannot_disband_player_team");
        }
        if (!actor.admin()) {
            if (team.type() == TeamType.SERVER) {
                throw TeamException.of("server_team_managed");
            }
            UUID player = actor.requirePlayer();
            if (!team.isMember(player)) {
                throw TeamException.of("not_in_that_team", TeamMessages.team(team));
            }
            requireRank(team, player, TeamRank.OWNER);
        }
        Component name = TeamMessages.team(team);
        delete(team, actor.player());
        return msg("disbanded", name);
    }

    // TeamDeletedEvent while the team is intact, then each member back to their player team (Left, Joined), then the
    // team and every other team's references to it go.
    static void delete(TeamData team, @Nullable UUID by) {
        Map<UUID, TeamRank> members = team.members();
        NeoForge.EVENT_BUS.post(new TeamDeletedEvent(team, members));
        Component name = team.displayName();
        for (UUID member : members.keySet()) {
            moveTo(member, teams().playerTeam(member), TeamRank.OWNER, PlayerLeftTeamEvent.Reason.DELETED);
            if (!member.equals(by)) {
                TeamMessages.send(server(), member, Kind.DISBANDED, msg("disbanded_you", name));
            }
        }
        for (TeamData other : teams().groups()) {
            if (other.equals(team)) {
                continue;
            }
            boolean wasAlly = other.removeAlly(team.id());
            boolean changed = wasAlly | other.removeAllyRequest(team.id()) | other.removeEnemy(team.id());
            if (changed) {
                teams().changed(other);
            }
            if (wasAlly) {
                NeoForge.EVENT_BUS.post(new TeamAlliesChangedEvent(other, Set.of(), Set.of(team.id())));
            }
        }
        teams().unregister(team);
    }

    // ---- Moving players ----

    // Moves a player into a team (their player team, a party or a server team), posting PlayerLeftTeamEvent if they
    // leave a party or server team and then PlayerJoinedTeamEvent.
    private static void moveTo(UUID player, TeamData to, TeamRank rank, PlayerLeftTeamEvent.Reason leaveReason) {
        TeamData from = teams().teamOf(player);
        ServerPlayer online = online(player);
        if (!from.type().isPlayer()) {
            from.removeMember(player);
            teams().indexMember(player, null);
            teams().changed(from);
            NeoForge.EVENT_BUS.post(new PlayerLeftTeamEvent(from, player, online, leaveReason));
        }
        if (!to.type().isPlayer()) {
            to.removeInvite(player);
            to.putMember(player, rank);
            teams().indexMember(player, to);
            teams().changed(to);
        }
        NeoForge.EVENT_BUS.post(new PlayerJoinedTeamEvent(player, online, from, to));
    }

    // Before an owner is taken out of their party by an admin: the next officer (else member) takes over, or the party
    // goes once it's empty. Returns what to do after the move.
    private static Runnable handOver(TeamData team, UUID player) {
        if (team.type() != TeamType.PARTY || team.memberRank(player) != TeamRank.OWNER) {
            return () -> {};
        }
        Optional<UUID> next = TeamManagerImpl.successor(team, player);
        if (next.isEmpty()) {
            return () -> delete(team, null);
        }
        team.putMember(next.get(), TeamRank.OWNER);
        return () -> {
            NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(team, Set.of(Property.OWNER, Property.RANKS)));
            TeamMessages.toTeam(server(), team, Kind.OWNERSHIP, msg("ownership_passed", name(next.get()), TeamMessages.team(team)), null);
        };
    }

    // ---- Admin ----

    // Puts a player in a party or server team, taking them out of their current one.
    public static Component forceJoin(UUID player, TeamData team) {
        if (team.type().isPlayer()) {
            throw TeamException.of("cannot_join_player_team");
        }
        if (team.isMember(player)) {
            throw TeamException.of("already_member", name(player), TeamMessages.team(team));
        }
        TeamData current = teams().teamOf(player);
        requestJoin(team, player, current);
        Runnable after = handOver(current, player);
        moveTo(player, team, TeamRank.MEMBER, PlayerLeftTeamEvent.Reason.SWITCHED);
        after.run();
        TeamMessages.send(server(), player, Kind.JOINED, msg("joined", TeamMessages.team(team)));
        TeamMessages.toTeam(server(), team, Kind.JOINED, msg("joined_team", name(player), TeamMessages.team(team)), player);
        return msg("force_joined", name(player), TeamMessages.team(team));
    }

    // Takes a player out of their party or server team, back to their player team.
    public static Component forceLeave(UUID player) {
        TeamData team = teams().teamOf(player);
        if (team.type().isPlayer()) {
            throw TeamException.of("player_not_in_party", name(player));
        }
        return removeFrom(team, player);
    }

    // An admin removing a player from a given team.
    public static Component removeFrom(TeamData team, UUID player) {
        if (!team.isMember(player)) {
            throw TeamException.of("not_member", name(player), TeamMessages.team(team));
        }
        Component teamName = TeamMessages.team(team);
        Runnable after = handOver(team, player);
        moveTo(player, teams().playerTeam(player), TeamRank.OWNER, PlayerLeftTeamEvent.Reason.KICKED);
        after.run();
        TeamMessages.send(server(), player, Kind.KICKED, msg("removed_you", teamName));
        if (!team.isDeleted()) {
            TeamMessages.toTeam(server(), team, Kind.LEFT, msg("left_team", name(player), teamName), player);
        }
        return msg("force_left", name(player), teamName);
    }

    // ---- Allies and enemies ----

    // Asks another party to be allies, or accepts if they already asked.
    public static Component requestAlly(Actor actor, TeamData target) {
        if (!TeamsConfig.ALLIES_ENABLED.getAsBoolean()) {
            throw TeamException.of("allies_disabled");
        }
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (target.equals(party)) {
            throw TeamException.of("cannot_ally_self");
        }
        if (target.type() != TeamType.PARTY) {
            throw TeamException.of("parties_only");
        }
        if (party.allies().contains(target.id())) {
            throw TeamException.of("already_allies", TeamMessages.team(target));
        }
        long now = System.currentTimeMillis();
        if (party.hasAllyRequest(target.id(), now)) {
            return makeAllies(party, target);
        }
        if (!target.allowAllyInvites()) {
            throw TeamException.of("ally_requests_closed", TeamMessages.team(target));
        }
        if (target.hasAllyRequest(party.id(), now)) {
            throw TeamException.of("ally_already_requested", TeamMessages.team(target));
        }
        if (!ALLY_REQUESTS.tryAcquire(player)) {
            throw TeamException.of("slow_down");
        }
        target.putAllyRequest(party.id(), expiresAt());
        teams().changed(target);
        String quoted = TeamMessages.quote(party.name());
        Component toast = msg("ally_request", TeamMessages.team(party));
        Component request = msg("ally_request", TeamMessages.team(party))
                .append(" ")
                .append(TeamMessages.button("hibiscusteams.button.accept", ChatFormatting.GREEN, TeamMessages.command("allies accept " + quoted), null))
                .append(" ")
                .append(TeamMessages.button("hibiscusteams.button.decline", ChatFormatting.RED, TeamMessages.command("allies decline " + quoted), null));
        TeamMessages.toOfficers(server(), target, Kind.ALLY_REQUEST, request, toast);
        return msg("ally_request_sent", TeamMessages.team(target));
    }

    public static Component acceptAlly(Actor actor, TeamData from) {
        if (!TeamsConfig.ALLIES_ENABLED.getAsBoolean()) {
            throw TeamException.of("allies_disabled");
        }
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (!party.hasAllyRequest(from.id(), System.currentTimeMillis())) {
            throw TeamException.of("no_ally_request", TeamMessages.team(from));
        }
        return makeAllies(party, from);
    }

    public static Component declineAlly(Actor actor, TeamData from) {
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (!party.removeAllyRequest(from.id())) {
            throw TeamException.of("no_ally_request", TeamMessages.team(from));
        }
        teams().changed(party);
        TeamMessages.toOfficers(server(), from, Kind.INFO, msg("ally_declined_team", TeamMessages.team(party)));
        return msg("ally_declined", TeamMessages.team(from));
    }

    // Ends an alliance (both sides), or withdraws this party's pending request.
    public static Component removeAlly(Actor actor, TeamData other) {
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (party.removeAlly(other.id())) {
            other.removeAlly(party.id());
            teams().changed(party);
            teams().changed(other);
            NeoForge.EVENT_BUS.post(new TeamAlliesChangedEvent(party, Set.of(), Set.of(other.id())));
            NeoForge.EVENT_BUS.post(new TeamAlliesChangedEvent(other, Set.of(), Set.of(party.id())));
            TeamMessages.toTeam(server(), party, Kind.ALLIES, msg("allies_ended", TeamMessages.team(other)), player);
            TeamMessages.toTeam(server(), other, Kind.ALLIES, msg("allies_ended", TeamMessages.team(party)), null);
            return msg("allies_ended", TeamMessages.team(other));
        }
        if (other.removeAllyRequest(party.id())) {
            teams().changed(other);
            return msg("ally_request_withdrawn", TeamMessages.team(other));
        }
        throw TeamException.of("not_allies", TeamMessages.team(other));
    }

    private static Component makeAllies(TeamData a, TeamData b) {
        a.addAlly(b.id());
        b.addAlly(a.id());
        a.removeAllyRequest(b.id());
        b.removeAllyRequest(a.id());
        a.removeEnemy(b.id());
        b.removeEnemy(a.id());
        teams().changed(a);
        teams().changed(b);
        NeoForge.EVENT_BUS.post(new TeamAlliesChangedEvent(a, Set.of(b.id()), Set.of()));
        NeoForge.EVENT_BUS.post(new TeamAlliesChangedEvent(b, Set.of(a.id()), Set.of()));
        TeamMessages.toTeam(server(), a, Kind.ALLIES, msg("allies_now", TeamMessages.team(b)), null);
        TeamMessages.toTeam(server(), b, Kind.ALLIES, msg("allies_now", TeamMessages.team(a)), null);
        return msg("allies_now", TeamMessages.team(b));
    }

    // Marks another team as an enemy of the actor's party (one-sided; no request).
    public static Component addEnemy(Actor actor, TeamData other) {
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (other.equals(party) || other.type().isPlayer()) {
            throw TeamException.of("cannot_enemy", TeamMessages.team(other));
        }
        if (party.allies().contains(other.id())) {
            throw TeamException.of("allied_cannot_enemy", TeamMessages.team(other));
        }
        if (party.enemies().contains(other.id())) {
            throw TeamException.of("already_enemies", TeamMessages.team(other));
        }
        party.addEnemy(other.id());
        teams().changed(party);
        NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(party, Set.of(Property.OTHER)));
        return msg("enemy_added", TeamMessages.team(other));
    }

    public static Component removeEnemy(Actor actor, TeamData other) {
        UUID player = actor.requirePlayer();
        TeamData party = requireParty(player);
        requireRank(party, player, TeamRank.OFFICER);
        if (!party.removeEnemy(other.id())) {
            throw TeamException.of("not_enemies", TeamMessages.team(other));
        }
        teams().changed(party);
        NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(party, Set.of(Property.OTHER)));
        return msg("enemy_removed", TeamMessages.team(other));
    }

    // ---- Team chat ----

    // Whether a player's chat goes to their team by default: their own toggle, else their party's setting.
    public static boolean teamChatByDefault(UUID player) {
        TeamData team = teams().teamOf(player);
        if (team.type().isPlayer()) {
            return false;
        }
        return teams().playerTeam(player).teamChat().orElse(team.teamChatDefault());
    }

    // Flips a player's team chat default; returns the new setting.
    public static boolean toggleTeamChat(UUID player) {
        if (teams().teamOf(player).type().isPlayer()) {
            throw TeamException.of("not_in_party");
        }
        boolean enabled = !teamChatByDefault(player);
        TeamData own = teams().playerTeam(player);
        own.setTeamChat(enabled);
        teams().changed(own);
        return enabled;
    }
}
