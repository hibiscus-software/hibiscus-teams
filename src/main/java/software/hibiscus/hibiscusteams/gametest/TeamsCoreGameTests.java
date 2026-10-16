/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.api.team.TeamsApi;
import software.hibiscus.hibiscuslib.api.team.event.PlayerJoinTeamRequestEvent;
import software.hibiscus.hibiscuslib.api.team.event.PlayerJoinedTeamEvent;
import software.hibiscus.hibiscuslib.api.team.event.PlayerLeftTeamEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamAlliesChangedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamCreatedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamDeletedEvent;
import software.hibiscus.hibiscuslib.team.SoloTeamStore;
import software.hibiscus.hibiscuslib.team.SoloTeams;
import software.hibiscus.hibiscusteams.HibiscusTeams;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.FriendlyFire;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamException;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;
import software.hibiscus.hibiscusteams.team.TeamStorage;
import software.hibiscus.hibiscusteams.team.TeamTestHooks;

// The team rules and the Teams API contract: parties, invites, ranks, allies, friendly fire, persistence, the events
// Hibiscus Quests depends on, and the move from Hibiscus Lib's fallback. Each test makes its own mock players and
// uniquely named teams and removes them afterwards, so tests can't see each other's state (the gametest world is
// kept between runs).
final class TeamsCoreGameTests {
    private static final Identifier QUESTS = Identifier.fromNamespaceAndPath("hibiscusquests", "progress");
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("othermod", "data");
    // Every Teams API event seen, as a line of text, in the order posted.
    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    // Players whose join requests get denied, as a quest mod might.
    private static final Set<UUID> DENY = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((PlayerJoinTeamRequestEvent event) -> {
            EVENTS.add("request " + event.playerId() + " " + event.team().id() + " from " + event.from().id());
            if (DENY.contains(event.playerId())) {
                event.deny(Component.literal("denied by test"));
            }
        });
        NeoForge.EVENT_BUS.addListener((TeamCreatedEvent event) -> EVENTS.add("created " + event.team().id()));
        NeoForge.EVENT_BUS.addListener((TeamDeletedEvent event) -> EVENTS.add("deleted " + event.team().id()
                + " members " + event.formerMembers().size() + " data " + event.team().extraData(QUESTS).getIntOr("value", -1)));
        NeoForge.EVENT_BUS.addListener((PlayerLeftTeamEvent event) -> EVENTS.add("left " + event.playerId() + " " + event.team().id()
                + " " + event.reason().name().toLowerCase(Locale.ROOT)));
        NeoForge.EVENT_BUS.addListener((PlayerJoinedTeamEvent event) -> EVENTS.add("joined " + event.playerId() + " from "
                + event.from().id() + " to " + event.to().id()
                + (TeamsApi.get().teamOf(event.playerId()).id().equals(event.to().id()) ? "" : " (teamOf disagrees)")));
        NeoForge.EVENT_BUS.addListener((TeamAlliesChangedEvent event) -> EVENTS.add("allies " + event.team().id()
                + " +" + event.added() + " -" + event.removed()));
    }

    private TeamsCoreGameTests() {}

    // ---- Helpers ----

    // The mock players and teams a test makes, cleaned up when it ends.
    private static final class Scope implements AutoCloseable {
        private final GameTestHelper helper;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<TeamData> teams = new ArrayList<>();

        Scope(GameTestHelper helper) {
            this.helper = helper;
        }

        // A mock player that has logged in.
        @SuppressWarnings("removal")
        UUID player() {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            players.add(player);
            return player.getUUID();
        }

        TeamData party(UUID owner) {
            return track(TeamOps.createParty(owner, name("Party")));
        }

        TeamData track(TeamData team) {
            teams.add(team);
            return team;
        }

        MinecraftServer server() {
            return helper.getLevel().getServer();
        }

        @Override
        public void close() {
            for (TeamData team : teams) {
                if (!team.isDeleted()) {
                    TeamOps.disband(Actor.admin(null), team);
                }
            }
            players.forEach(player -> server().getPlayerList().remove(player));
        }
    }

    private static String name(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static TeamManagerImpl teams() {
        return TeamManagerImpl.INSTANCE;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    // Runs something that must be refused with a TeamException.
    private static void refused(GameTestHelper helper, Runnable action, String message) {
        try {
            action.run();
        } catch (TeamException expected) {
            return;
        }
        helper.fail(message);
    }

    private static void invited(UUID owner, UUID player, TeamData party) {
        TeamOps.invite(Actor.player(owner), player);
        TeamOps.accept(player, party);
    }

    // ---- Tests ----

    static void apiInstalled(GameTestHelper helper) {
        check(helper, TeamsApi.get() == TeamManagerImpl.INSTANCE, "TeamsApi.get() should be Hibiscus Teams' manager");
        check(helper, !TeamsApi.isFallback(), "the fallback shouldn't be in use");
        check(helper, TeamsApi.installedBy().equals(Optional.of(HibiscusTeams.MODID)), "installedBy should be hibiscusteams");
        helper.succeed();
    }

    // A player's own team keeps the fallback's shape: id = UUID, sole OWNER, named after the player.
    static void playerTeamShape(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID player = scope.player();
            TeamData team = teams().teamOf(player);
            check(helper, team.id().equals(player), "a player team's id is the player's UUID");
            check(helper, team.type() == TeamType.PLAYER, "type should be PLAYER");
            check(helper, team.members().equals(Map.of(player, TeamRank.OWNER)), "the player is its only member, as OWNER");
            check(helper, team.owner().equals(Optional.of(player)), "the player owns it");
            check(helper, team.shortName().equals("test-mock-player"), "its short name is the player's name");
            check(helper, team == teams().playerTeam(player), "teamOf and playerTeam agree outside a party");
            check(helper, teams().team(player).isPresent(), "team(id) finds it");
            check(helper, EVENTS.contains("created " + player), "TeamCreatedEvent should fire on first join");
            helper.succeed();
        }
    }

    static void inviteAcceptMember(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            check(helper, teams().teamOf(owner) == party && party.rankOf(owner) == TeamRank.OWNER, "the creator owns the new party");
            TeamOps.invite(Actor.player(owner), guest);
            check(helper, party.rankOf(guest) == TeamRank.INVITED && !party.isMember(guest), "an invited player is INVITED, not a member");
            check(helper, !party.members().containsKey(guest), "members() leaves out invited players");
            TeamOps.accept(guest, null);
            check(helper, teams().teamOf(guest) == party, "accepting moves the player into the party");
            check(helper, party.rankOf(guest) == TeamRank.MEMBER && party.members().size() == 2, "they join as MEMBER");
            check(helper, party.invites().isEmpty(), "the invite is used up");
            helper.succeed();
        }
    }

    static void leaveReturnsPlayerTeam(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, guest, party);
            TeamOps.leave(guest);
            TeamData back = teams().teamOf(guest);
            check(helper, back.id().equals(guest) && back.type() == TeamType.PLAYER, "leaving returns the player to their player team");
            check(helper, back == teams().playerTeam(guest), "it's the same player team as before");
            check(helper, !party.isMember(guest), "they're no longer a member");
            refused(helper, () -> TeamOps.leave(guest), "leaving with no party should be refused");
            helper.succeed();
        }
    }

    static void ownerCannotLeave(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, guest, party);
            refused(helper, () -> TeamOps.leave(owner), "the owner shouldn't be able to leave");
            TeamOps.transferOwnership(Actor.player(owner), guest);
            check(helper, party.owner().equals(Optional.of(guest)) && party.rankOf(owner) == TeamRank.OFFICER,
                    "after a transfer the new owner owns it and the old one is an officer");
            TeamOps.leave(owner);
            check(helper, teams().teamOf(owner).type().isPlayer(), "after transferring, the old owner can leave");
            helper.succeed();
        }
    }

    static void kick(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID officer = scope.player();
            UUID member = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, officer, party);
            invited(owner, member, party);
            TeamOps.promote(Actor.player(owner), officer);
            TeamOps.kick(Actor.player(officer), member);
            check(helper, !party.isMember(member) && teams().teamOf(member).type().isPlayer(), "an officer can kick a member");
            UUID invitee = scope.player();
            TeamOps.invite(Actor.player(owner), invitee);
            TeamOps.kick(Actor.player(owner), invitee);
            check(helper, party.rankOf(invitee) == TeamRank.NONE, "kicking an invited player withdraws the invite");
            helper.succeed();
        }
    }

    static void rankLimits(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID officer = scope.player();
            UUID officer2 = scope.player();
            UUID member = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, officer, party);
            invited(owner, officer2, party);
            invited(owner, member, party);
            TeamOps.promote(Actor.player(owner), officer);
            TeamOps.promote(Actor.player(owner), officer2);
            refused(helper, () -> TeamOps.kick(Actor.player(officer), officer2), "officers can't kick officers");
            refused(helper, () -> TeamOps.kick(Actor.player(officer), owner), "officers can't kick the owner");
            refused(helper, () -> TeamOps.kick(Actor.player(member), officer), "members can't kick");
            refused(helper, () -> TeamOps.kick(Actor.player(owner), owner), "nobody can kick themselves");
            refused(helper, () -> TeamOps.promote(Actor.player(officer), member), "only the owner promotes");
            refused(helper, () -> TeamOps.promote(Actor.player(owner), officer), "an officer can't be promoted further");
            refused(helper, () -> TeamOps.invite(Actor.player(member), UUID.randomUUID()), "members can't invite");
            refused(helper, () -> TeamOps.set(Actor.player(member), party, TeamSetting.TAG, "NOPE"), "members can't change settings");
            TeamOps.demote(Actor.player(owner), officer2);
            check(helper, party.rankOf(officer2) == TeamRank.MEMBER, "the owner can demote an officer");
            refused(helper, () -> TeamOps.demote(Actor.player(owner), officer2), "a member can't be demoted further");
            TeamOps.kick(Actor.player(owner), officer);
            check(helper, !party.isMember(officer), "the owner can kick an officer");
            helper.succeed();
        }
    }

    static void renameUnique(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID a = scope.player();
            UUID b = scope.player();
            UUID c = scope.player();
            TeamData first = scope.party(a);
            TeamData second = scope.party(b);
            String taken = first.name();
            refused(helper, () -> TeamOps.set(Actor.player(b), second, TeamSetting.NAME, taken.toUpperCase(Locale.ROOT)),
                    "renaming to another team's name (any case) should be refused");
            refused(helper, () -> scope.track(TeamOps.createParty(c, "  " + taken.toLowerCase(Locale.ROOT) + " ")),
                    "creating a party with a taken name should be refused");
            TeamOps.set(Actor.player(a), first, TeamSetting.NAME, taken.toLowerCase(Locale.ROOT));
            check(helper, first.name().equals(taken.toLowerCase(Locale.ROOT)), "a team can change the case of its own name");
            refused(helper, () -> TeamOps.set(Actor.player(a), first, TeamSetting.NAME, " "), "an empty name should be refused");
            refused(helper, () -> TeamOps.set(Actor.player(a), first, TeamSetting.NAME, "x".repeat(33)), "a 33-character name should be refused");
            refused(helper, () -> TeamOps.set(Actor.player(a), first, TeamSetting.TAG, "TOOLONG"), "a 7-character tag should be refused");
            refused(helper, () -> TeamOps.set(Actor.player(a), first, TeamSetting.TAG, "A B"), "a tag with a space should be refused");
            helper.succeed();
        }
    }

    static void freeToJoin(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            refused(helper, () -> TeamOps.joinParty(guest, party), "joining a closed party without an invite should be refused");
            TeamOps.set(Actor.player(owner), party, TeamSetting.FREE_TO_JOIN, "true");
            TeamOps.joinParty(guest, party);
            check(helper, party.isMember(guest), "anyone can join a free-to-join party");
            TeamOps.set(Actor.player(owner), party, TeamSetting.MAX_MEMBERS, "2");
            UUID third = scope.player();
            refused(helper, () -> TeamOps.joinParty(third, party), "a full party should refuse joins");
            helper.succeed();
        }
    }

    static void inviteExpiry(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            TeamOps.invite(Actor.player(owner), guest);
            TeamTestHooks.expireInvite(party, guest);
            check(helper, party.rankOf(guest) == TeamRank.NONE, "a lapsed invite doesn't count");
            refused(helper, () -> TeamOps.accept(guest, party), "a lapsed invite can't be accepted");
            refused(helper, () -> TeamOps.accept(guest, null), "a lapsed invite isn't offered");
            TeamTestHooks.pruneExpired();
            check(helper, party.invites().isEmpty(), "lapsed invites are cleaned up");
            helper.succeed();
        }
    }

    static void allies(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID a = scope.player();
            UUID b = scope.player();
            TeamData first = scope.party(a);
            TeamData second = scope.party(b);
            refused(helper, () -> TeamOps.requestAlly(Actor.player(a), first), "a party can't ally itself");
            TeamOps.requestAlly(Actor.player(a), second);
            check(helper, second.hasAllyRequest(first.id(), System.currentTimeMillis()), "the request waits on the other party");
            check(helper, !first.isAlly(second.id()) && second.rankOf(a) == TeamRank.NONE, "a request isn't an alliance yet");
            TeamOps.acceptAlly(Actor.player(b), first);
            check(helper, first.isAlly(second.id()) && second.isAlly(first.id()), "accepting allies both parties");
            check(helper, second.rankOf(a) == TeamRank.ALLY, "members of an allied party rank ALLY");
            check(helper, second.allyRequests().isEmpty(), "the request is used up");
            TeamOps.removeAlly(Actor.player(b), first);
            check(helper, first.allies().isEmpty() && second.allies().isEmpty(), "removing ends the alliance on both sides");
            TeamOps.set(Actor.player(b), second, TeamSetting.ALLOW_ALLY_INVITES, "false");
            refused(helper, () -> TeamOps.requestAlly(Actor.player(a), second), "a party not taking ally requests refuses them");
            helper.succeed();
        }
    }

    static void friendlyFire(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID member = scope.player();
            UUID stranger = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, member, party);
            check(helper, FriendlyFire.isBlocked(owner, member) && FriendlyFire.isBlocked(member, owner), "friendly fire is off by default");
            check(helper, !FriendlyFire.isBlocked(owner, stranger) && !FriendlyFire.isBlocked(stranger, owner), "outsiders aren't protected");
            TeamOps.set(Actor.player(owner), party, TeamSetting.FRIENDLY_FIRE, "true");
            check(helper, !FriendlyFire.isBlocked(owner, member), "with friendly_fire on, members can hurt each other");
            helper.succeed();
        }
    }

    // extraData is saved with the team (party and player team alike), survives a rename, and markDirty schedules it.
    static void extraDataPersists(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            TeamData party = scope.party(owner);
            party.extraData(QUESTS).putInt("completed", 7);
            party.extraData(OTHER).putString("note", "hello");
            teams().saveNow();
            party.markDirty();
            check(helper, teams().isDirty(party.id()), "markDirty should schedule a save");
            String renamed = name("Renamed");
            TeamOps.set(Actor.player(owner), party, TeamSetting.NAME, renamed);
            TeamData loaded = TeamTestHooks.saveAndReload(party).orElseThrow();
            check(helper, loaded.name().equals(renamed), "the new name is saved");
            check(helper, loaded.extraData(QUESTS).getIntOr("completed", -1) == 7, "extraData survives a save, reload and rename");
            check(helper, loaded.extraData(OTHER).getStringOr("note", "").equals("hello") && !loaded.extraData(QUESTS).contains("note"),
                    "each mod's extraData is kept separately");

            TeamData own = teams().playerTeam(owner);
            own.extraData(QUESTS).putInt("solo", 3);
            own.markDirty();
            check(helper, TeamTestHooks.saveAndReload(own).orElseThrow().extraData(QUESTS).getIntOr("solo", -1) == 3,
                    "a player team's extraData is saved too, even while they're in a party");

            TeamOps.disband(Actor.player(owner), party);
            CompoundTag late = party.extraData(QUESTS);
            late.putInt("late", 1);
            check(helper, !party.extraData(QUESTS).contains("late"), "a deleted team's extraData isn't kept");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("reading the saved team failed: " + e.getMessage());
        }
    }

    // The Teams API events, in the order Hibiscus Lib documents, with the right from/to teams.
    static void eventsInOrder(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID a = scope.player();
            UUID b = scope.player();
            int start = EVENTS.size();
            TeamData party = scope.party(a);
            UUID p = party.id();
            party.extraData(QUESTS).putInt("value", 42);
            invited(a, b, party);
            TeamOps.leave(b);
            invited(a, b, party);
            TeamOps.kick(Actor.player(a), b);
            invited(a, b, party);
            TeamOps.disband(Actor.player(a), party);

            List<String> expected = List.of(
                    "request " + a + " " + p + " from " + a,
                    "created " + p,
                    "joined " + a + " from " + a + " to " + p,
                    "request " + b + " " + p + " from " + b,
                    "joined " + b + " from " + b + " to " + p,
                    "left " + b + " " + p + " left",
                    "joined " + b + " from " + p + " to " + b,
                    "request " + b + " " + p + " from " + b,
                    "joined " + b + " from " + b + " to " + p,
                    "left " + b + " " + p + " kicked",
                    "joined " + b + " from " + p + " to " + b,
                    "request " + b + " " + p + " from " + b,
                    "joined " + b + " from " + b + " to " + p,
                    "deleted " + p + " members 2 data 42",
                    "left " + a + " " + p + " deleted",
                    "joined " + a + " from " + p + " to " + a,
                    "left " + b + " " + p + " deleted",
                    "joined " + b + " from " + p + " to " + b);
            List<String> seen;
            synchronized (EVENTS) {
                seen = EVENTS.subList(start, EVENTS.size()).stream()
                        .filter(line -> line.contains(a.toString()) || line.contains(b.toString()) || line.contains(p.toString()))
                        .toList();
            }
            check(helper, seen.equals(expected), "events out of order or wrong:\n  expected " + expected + "\n  got      " + seen);
            helper.succeed();
        }
    }

    // A cancelled PlayerJoinTeamRequestEvent stops the join, with its reason, and changes nothing.
    static void joinRequestDenied(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID guest = scope.player();
            TeamData party = scope.party(owner);
            TeamOps.invite(Actor.player(owner), guest);
            DENY.add(guest);
            try {
                TeamOps.accept(guest, party);
                helper.fail("a denied join should be refused");
            } catch (TeamException e) {
                check(helper, e.reason().getString().equals("denied by test"), "the deny reason should be shown, got " + e.reason().getString());
            }
            check(helper, !party.isMember(guest) && party.rankOf(guest) == TeamRank.INVITED, "a denied join leaves the invite in place");
            String name = name("Denied");
            refused(helper, () -> scope.track(TeamOps.createParty(guest, name)), "a denied creator can't make a party");
            check(helper, teams().byName(name).isEmpty(), "a denied party creation leaves no party behind");
            DENY.remove(guest);
            TeamOps.accept(guest, party);
            check(helper, party.isMember(guest), "once allowed, the join goes through");
            helper.succeed();
        } finally {
            DENY.clear();
        }
    }

    static void serverTeams(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID player = scope.player();
            TeamData team = scope.track(TeamOps.createServerTeam(null, name("Server")));
            check(helper, team.type() == TeamType.SERVER && team.owner().isEmpty(), "a server team has no owner");
            TeamOps.forceJoin(player, team);
            check(helper, teams().teamOf(player) == team && team.rankOf(player) == TeamRank.MEMBER, "admins add members");
            refused(helper, () -> TeamOps.set(Actor.player(player), team, TeamSetting.TAG, "X"), "members can't edit a server team");
            TeamOps.leave(player);
            check(helper, teams().teamOf(player).type().isPlayer(), "members can leave an unlocked server team");
            TeamOps.forceJoin(player, team);
            TeamOps.set(Actor.admin(null), team, TeamSetting.LOCKED, "true");
            refused(helper, () -> TeamOps.leave(player), "members can't leave a locked server team");
            TeamOps.removeFrom(team, player);
            check(helper, !team.isMember(player), "admins can still remove them");
            UUID owner = scope.player();
            TeamData party = scope.party(owner);
            refused(helper, () -> TeamOps.set(Actor.player(owner), party, TeamSetting.LOCKED, "true"), "parties have no locked setting");
            helper.succeed();
        }
    }

    // An admin removing a party's owner hands it to the first officer, then the first member, then deletes it.
    static void adminForceLeaveOwner(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            UUID member = scope.player();
            UUID officer = scope.player();
            TeamData party = scope.party(owner);
            invited(owner, member, party);
            invited(owner, officer, party);
            TeamOps.promote(Actor.player(owner), officer);
            TeamOps.forceLeave(owner);
            check(helper, party.owner().equals(Optional.of(officer)), "the officer should take over");
            TeamOps.forceLeave(officer);
            check(helper, party.owner().equals(Optional.of(member)), "then the member");
            TeamOps.forceLeave(member);
            check(helper, party.isDeleted() && teams().team(party.id()).isEmpty(), "an emptied party is deleted");
            helper.succeed();
        }
    }

    // Data kept under Hibiscus Lib's fallback is copied into player teams without overwriting what's there.
    static void fallbackMigrated(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            UUID player = scope.player();
            SoloTeams.INSTANCE.playerTeam(player).extraData(OTHER).putInt("legacy", 3);
            SoloTeams.INSTANCE.playerTeam(player).extraData(QUESTS).putInt("legacy", 3);
            teams().playerTeam(player).extraData(QUESTS).putInt("kept", 1);
            UUID offline = UUID.randomUUID();
            SoloTeams.INSTANCE.playerTeam(offline).extraData(QUESTS).putInt("legacy", 5);

            teams().migrate(SoloTeamStore.get(scope.server()).entries());

            TeamData own = teams().playerTeam(player);
            check(helper, own.extraData(OTHER).getIntOr("legacy", -1) == 3, "a mod's fallback data is copied in");
            check(helper, own.extraData(QUESTS).getIntOr("kept", -1) == 1 && !own.extraData(QUESTS).contains("legacy"),
                    "data the player team already has for a mod is kept, not overwritten");
            Optional<TeamData> copied = teams().teamData(offline);
            check(helper, copied.isPresent() && copied.get().type().isPlayer(), "a player known only to the fallback gets a player team");
            check(helper, copied.get().extraData(QUESTS).getIntOr("legacy", -1) == 5, "with their data");
            check(helper, copied.get().name().equals(SoloTeamStore.get(scope.server()).entry(offline).orElseThrow().name()),
                    "and their name");
            helper.succeed();
        }
    }

    // A team file that won't load is reported and skipped, and never written over or deleted.
    static void brokenFileUntouched(GameTestHelper helper) {
        Path directory = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("hibiscusteams-test-" + UUID.randomUUID());
        try (Scope scope = new Scope(helper)) {
            UUID owner = scope.player();
            TeamData party = scope.party(owner);
            TeamStorage storage = new TeamStorage(directory);
            Files.createDirectories(directory);
            String broken = "{ id: \"" + party.id() + "\", name: \"half an edit";
            Files.writeString(storage.file(party.id()), broken, StandardCharsets.UTF_8);
            check(helper, storage.loadAll().isEmpty() && storage.isBroken(party.id()), "a file that won't parse is skipped and marked broken");
            check(helper, !storage.write(party), "a broken file isn't saved over");
            storage.delete(party.id());
            check(helper, Files.readString(storage.file(party.id()), StandardCharsets.UTF_8).equals(broken), "or deleted");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("file access failed: " + e.getMessage());
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
                // the test world is throwaway
            }
        }
    }
}
