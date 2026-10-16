/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.integration.ScoreboardMirror;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamException;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// Chat and the scoreboard: the vanilla scoreboard mirror (members, colour, tag; gone when the team is or when the
// mirror is switched off), the [TAG] on display names, and team chat taking a member's chat (or not, with !, or with
// team chat off). Config values changed here are put back afterwards.
final class IntegrationGameTests {
    private IntegrationGameTests() {}

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    // The players and teams a test makes, and config it changes; all undone when it ends.
    private static final class Scope implements AutoCloseable {
        private final GameTestHelper helper;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<TeamData> teams = new ArrayList<>();
        private final List<Runnable> restore = new ArrayList<>();
        private final String suffix = UUID.randomUUID().toString().substring(0, 5);
        private int count;

        Scope(GameTestHelper helper) {
            this.helper = helper;
        }

        ServerPlayer player() {
            ServerPlayer player = CommandGameTests.namedPlayer(helper, "ti" + suffix + (count++));
            players.add(player);
            return player;
        }

        TeamData party(ServerPlayer owner, ServerPlayer... members) {
            TeamData party = TeamOps.createParty(owner.getUUID(), "Int " + suffix + " " + teams.size());
            teams.add(party);
            for (ServerPlayer member : members) {
                TeamOps.forceJoin(member.getUUID(), party);
            }
            return party;
        }

        void set(ModConfigSpec.BooleanValue value, boolean to) {
            boolean before = value.getAsBoolean();
            value.set(to);
            restore.add(() -> value.set(before));
        }

        MinecraftServer server() {
            return helper.getLevel().getServer();
        }

        @Override
        public void close() {
            restore.reversed().forEach(Runnable::run);
            for (TeamData team : teams) {
                if (!team.isDeleted()) {
                    TeamOps.disband(Actor.admin(null), team);
                }
            }
            players.forEach(player -> server().getPlayerList().remove(player));
            ScoreboardMirror.update(server());
        }
    }

    private static String name(ServerPlayer player) {
        return player.nameAndId().name();
    }

    static void scoreboardMirror(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            scope.set(TeamsConfig.DISPLAY_SCOREBOARD_MIRROR, true);
            scope.set(TeamsConfig.DISPLAY_NAME_TAG_COLORS, true);
            scope.set(TeamsConfig.CHAT_SHOW_TAG, true);
            ServerPlayer owner = scope.player();
            ServerPlayer member = scope.player();
            ServerPlayer outsider = scope.player();
            TeamData party = scope.party(owner, member);
            TeamOps.set(Actor.admin(null), party, TeamSetting.TAG, "MIR");
            TeamOps.set(Actor.admin(null), party, TeamSetting.COLOR, "#F2557A");
            Scoreboard scoreboard = scope.server().getScoreboard();
            ScoreboardMirror.update(scope.server());

            String id = ScoreboardMirror.scoreboardName(party.id());
            PlayerTeam mirrored = scoreboard.getPlayerTeam(id);
            check(helper, mirrored != null && id.length() <= 16, "a party gets a short-named scoreboard team");
            check(helper, mirrored.getPlayers().containsAll(List.of(name(owner), name(member))) && !mirrored.getPlayers().contains(name(outsider)),
                    "the scoreboard team holds exactly the members");
            check(helper, mirrored.getPlayerPrefix().getString().equals("[MIR] "), "its prefix is the tag, got " + mirrored.getPlayerPrefix().getString());
            check(helper, mirrored.getColor().equals(Optional.of(ScoreboardMirror.nearest(0xF2557A))), "its colour is the nearest team colour");
            check(helper, owner.getDisplayName().getString().equals("[MIR] " + name(owner)), "the display name carries the tag once, got "
                    + owner.getDisplayName().getString());

            TeamOps.kick(Actor.player(owner.getUUID()), member.getUUID());
            ScoreboardMirror.update(scope.server());
            check(helper, !mirrored.getPlayers().contains(name(member)) && scoreboard.getPlayersTeam(name(member)) == null,
                    "a kicked member leaves the scoreboard team");

            TeamOps.set(Actor.admin(null), party, TeamSetting.TAG, "NEW");
            ScoreboardMirror.update(scope.server());
            check(helper, mirrored.getPlayerPrefix().getString().equals("[NEW] "), "a new tag reaches the scoreboard");

            scope.set(TeamsConfig.DISPLAY_NAME_TAG_COLORS, false);
            ScoreboardMirror.update(scope.server());
            check(helper, mirrored.getColor().isEmpty(), "with nameTagColors off the scoreboard team has no colour");

            scope.set(TeamsConfig.DISPLAY_SCOREBOARD_MIRROR, false);
            ScoreboardMirror.update(scope.server());
            check(helper, scoreboard.getPlayerTeam(id) == null && scoreboard.getPlayersTeam(name(owner)) == null,
                    "switching the mirror off removes the mirrored teams");

            scope.set(TeamsConfig.DISPLAY_SCOREBOARD_MIRROR, true);
            ScoreboardMirror.update(scope.server());
            TeamOps.disband(Actor.admin(null), party);
            ScoreboardMirror.update(scope.server());
            check(helper, scoreboard.getPlayerTeam(id) == null, "a disbanded party's scoreboard team goes");
            helper.succeed();
        }
    }

    // Without the mirror, the tag goes on the display name; and with showTag off, nowhere.
    static void chatTag(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            scope.set(TeamsConfig.DISPLAY_SCOREBOARD_MIRROR, false);
            scope.set(TeamsConfig.CHAT_SHOW_TAG, true);
            ScoreboardMirror.update(scope.server());
            ServerPlayer owner = scope.player();
            ServerPlayer loner = scope.player();
            TeamData party = scope.party(owner);
            TeamOps.set(Actor.admin(null), party, TeamSetting.TAG, "TAG");
            owner.refreshDisplayName();
            loner.refreshDisplayName();
            check(helper, owner.getDisplayName().getString().equals("[TAG] " + name(owner)), "a member's display name starts with their tag, got "
                    + owner.getDisplayName().getString());
            check(helper, loner.getDisplayName().getString().equals(name(loner)), "a player on their own has no tag");
            scope.set(TeamsConfig.CHAT_SHOW_TAG, false);
            owner.refreshDisplayName();
            check(helper, owner.getDisplayName().getString().equals(name(owner)), "with showTag off there's no tag");
            helper.succeed();
        }
    }

    private static ServerChatEvent chat(ServerPlayer player, String text) {
        return NeoForge.EVENT_BUS.post(new ServerChatEvent(player, text, Component.literal(text)));
    }

    static void teamChatRedirect(GameTestHelper helper) {
        try (Scope scope = new Scope(helper)) {
            scope.set(TeamsConfig.CHAT_TEAM_CHAT_ENABLED, true);
            ServerPlayer owner = scope.player();
            ServerPlayer member = scope.player();
            ServerPlayer loner = scope.player();
            TeamData party = scope.party(owner, member);
            check(helper, !chat(owner, "hello").isCanceled(), "chat is public by default");
            check(helper, !chat(loner, "hello").isCanceled(), "a player on their own always chats publicly");

            TeamOps.set(Actor.admin(null), party, TeamSetting.TEAM_CHAT_DEFAULT, "true");
            check(helper, chat(owner, "plans").isCanceled(), "with team_chat_default the message goes to the team instead");
            ServerChatEvent shout = chat(owner, "!everyone");
            check(helper, !shout.isCanceled() && shout.getMessage().getString().equals("everyone"), "a leading ! sends it to everyone, without the !");

            TeamOps.toggleTeamChat(member.getUUID());
            check(helper, !chat(member, "hi all").isCanceled(), "a member's own /teams chat overrides the party default");
            check(helper, chat(owner, "plans").isCanceled(), "while the others keep the default");

            scope.set(TeamsConfig.CHAT_TEAM_CHAT_ENABLED, false);
            check(helper, !chat(owner, "plans").isCanceled(), "with team chat off everything is public");
            try {
                TeamOps.toggleTeamChat(owner.getUUID());
                helper.fail("toggling team chat should be refused while it's off");
            } catch (TeamException expected) {
                // refused, as it should be
            }
            helper.succeed();
        }
    }

    // The colour match lands on sensible vanilla colours for the presets.
    static void nearestColor(GameTestHelper helper) {
        check(helper, ScoreboardMirror.nearest(0xFF5555) == TeamColor.RED, "pure-ish red is RED");
        check(helper, ScoreboardMirror.nearest(0x55FFFF) == TeamColor.AQUA, "vanilla aqua is AQUA");
        check(helper, ScoreboardMirror.nearest(0xF07AC0) == TeamColor.LIGHT_PURPLE, "the pink preset is LIGHT_PURPLE, got " + ScoreboardMirror.nearest(0xF07AC0));
        check(helper, ScoreboardMirror.nearest(0xE6E6E6) == TeamColor.WHITE, "near white is WHITE");
        helper.succeed();
    }
}
