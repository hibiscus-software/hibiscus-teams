/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamOps;

// The commands as typed: argument parsing, quoted team names, player names, the disband confirmation, and admin
// commands being out of reach for players. Runs through the real dispatcher as mock players and as the console.
final class CommandGameTests {
    private CommandGameTests() {}

    // A logged-in mock player with their own name (the gametest helper names every mock player the same, and these
    // commands find players by name).
    static ServerPlayer namedPlayer(GameTestHelper helper, String name) {
        MinecraftServer server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.CREATIVE;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    // Runs a command; returns its result (1 for success, 0 when Hibiscus Teams refused it), or -1 if it didn't parse
    // or the source isn't allowed to use it.
    private static int run(CommandSourceStack source, String command) {
        try {
            return source.getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            return -1;
        }
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static void expect(GameTestHelper helper, CommandSourceStack source, String command, int result) {
        int actual = run(source, command);
        if (actual != result) {
            helper.fail("/" + command + " gave " + actual + ", expected " + result);
        }
    }

    static void commandFlow(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TeamManagerImpl teams = TeamManagerImpl.INSTANCE;
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        List<ServerPlayer> players = new ArrayList<>();
        List<TeamData> made = new ArrayList<>();
        try {
            ServerPlayer owner = namedPlayer(helper, "tmA" + suffix);
            ServerPlayer guest = namedPlayer(helper, "tmB" + suffix);
            ServerPlayer admin = namedPlayer(helper, "tmC" + suffix);
            players.addAll(List.of(owner, guest, admin));
            CommandSourceStack a = owner.createCommandSourceStack();
            CommandSourceStack b = guest.createCommandSourceStack();
            CommandSourceStack console = server.createCommandSourceStack();
            String partyName = "Cmd Party " + suffix;

            expect(helper, a, "teams party create " + partyName, 1);
            TeamData party = teams.byName(partyName).orElseThrow();
            made.add(party);
            check(helper, teams.teamOf(owner) == party, "party create puts the creator in the party");
            expect(helper, a, "teams party create Another " + suffix, 0);
            expect(helper, a, "teams party invite " + guest.nameAndId().name(), 1);
            expect(helper, b, "teams party accept", 1);
            check(helper, party.isMember(guest.getUUID()), "party accept with no team accepts the only invite");
            expect(helper, a, "teams party promote " + guest.nameAndId().name(), 1);
            check(helper, party.rankOf(guest.getUUID()) == TeamRank.OFFICER, "party promote");
            expect(helper, a, "teams party settings tag CMD", 1);
            expect(helper, a, "teams party settings color #4FC78A", 1);
            expect(helper, a, "teams party settings free_to_join true", 1);
            expect(helper, a, "teams party settings max_members lots", 0);
            expect(helper, a, "teams party settings no_such_key 1", 0);
            check(helper, party.tag().equals("CMD") && party.color() == 0x4FC78A && party.freeToJoin(), "party settings sets values");
            expect(helper, a, "teams party settings", 1);
            expect(helper, b, "teams party leave", 1);
            expect(helper, b, "teams party join \"" + partyName + "\"", 1);
            check(helper, party.isMember(guest.getUUID()), "party join takes a quoted name");
            expect(helper, b, "teams party leave", 1);
            expect(helper, b, "teams party join " + partyName.toUpperCase(java.util.Locale.ROOT), 1);
            check(helper, party.isMember(guest.getUUID()), "party join takes an unquoted name in any case");
            expect(helper, a, "teams info", 1);
            expect(helper, b, "teams info " + owner.nameAndId().name(), 1);
            expect(helper, b, "teams info \"" + partyName + "\"", 1);
            expect(helper, b, "teams list", 1);
            expect(helper, b, "teams msg hello team", 1);
            expect(helper, b, "teams chat", 1);
            check(helper, TeamOps.teamChatByDefault(guest.getUUID()), "teams chat turns team chat on");

            // Admin commands aren't there for players who aren't ops.
            expect(helper, b, "teams admin disband \"" + partyName + "\"", -1);
            expect(helper, b, "teams server create Nope " + suffix, -1);
            check(helper, !party.isDeleted(), "a non-op can't run admin commands");

            String renamed = "Renamed " + suffix;
            expect(helper, console, "teams admin rename \"" + partyName + "\" " + renamed, 1);
            check(helper, party.name().equals(renamed), "admin rename");

            String serverName = "Srv " + suffix;
            expect(helper, console, "teams server create " + serverName, 1);
            TeamData serverTeam = teams.byName(serverName).orElseThrow();
            made.add(serverTeam);
            check(helper, serverTeam.type() == TeamType.SERVER, "server create makes a server team");
            expect(helper, console, "teams server add \"" + serverName + "\" " + admin.nameAndId().name(), 1);
            check(helper, teams.teamOf(admin) == serverTeam, "server add");
            expect(helper, console, "teams server settings \"" + serverName + "\" locked true", 1);
            check(helper, serverTeam.locked(), "server settings");
            expect(helper, admin.createCommandSourceStack(), "teams party leave", 0);
            expect(helper, console, "teams admin force_join " + admin.nameAndId().name() + " " + renamed, 1);
            check(helper, teams.teamOf(admin) == party, "admin force_join moves a player between teams");
            expect(helper, console, "teams admin force_leave " + admin.nameAndId().name(), 1);
            check(helper, teams.teamOf(admin).type().isPlayer(), "admin force_leave");

            expect(helper, a, "teams party disband", 1);
            check(helper, !party.isDeleted(), "party disband asks for confirmation first");
            expect(helper, a, "teams party disband confirm", 1);
            check(helper, party.isDeleted() && teams.teamOf(guest).type().isPlayer(), "party disband confirm disbands");
            expect(helper, console, "teams server delete " + serverName, 1);
            check(helper, serverTeam.isDeleted(), "server delete");
            check(helper, teams.byName(serverName).isEmpty() && teams.byName(renamed).isEmpty(),
                    "deleted teams' names are free again");
            helper.succeed();
        } finally {
            for (TeamData team : made) {
                if (!team.isDeleted()) {
                    TeamOps.disband(Actor.admin(null), team);
                }
            }
            players.forEach(player -> server.getPlayerList().remove(player));
        }
    }
}
