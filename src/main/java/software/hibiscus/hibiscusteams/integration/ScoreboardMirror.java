/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.integration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import software.hibiscus.hibiscuslib.util.Profiles;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;

// Mirrors each party and server team to a vanilla scoreboard team, so the player list and name tags show it (colour
// and [TAG]) and vanilla clients and other mods see the teams. The scoreboard teams are named PREFIX plus part of the
// team's id (scoreboard names are short; display names can change). Brought up to date at the end of a tick in which
// anything changed; with the mirror switched off, the mirrored teams are removed and nothing else is touched.
public final class ScoreboardMirror {
    public static final String PREFIX = "hbt_";
    // Checked this often even without a change, to follow config edits and player name changes.
    private static final int REFRESH_TICKS = 100;

    private static boolean dirty = true;
    private static long tick;

    private ScoreboardMirror() {}

    public static void register() {
        TeamManagerImpl.INSTANCE.addChangeListener(team -> dirty = true);
    }

    public static void markDirty() {
        dirty = true;
    }

    // The scoreboard team name for a team: hbt_ and the first 12 hex digits of its id.
    public static String scoreboardName(UUID teamId) {
        return PREFIX + teamId.toString().replace("-", "").substring(0, 12);
    }

    public static void tick(MinecraftServer server) {
        if (!TeamManagerImpl.INSTANCE.isRunning()) {
            return;
        }
        if (!dirty && ++tick % REFRESH_TICKS != 0) {
            return;
        }
        dirty = false;
        update(server);
    }

    // Makes the scoreboard match the teams (or, with the mirror off, removes the mirrored teams).
    public static void update(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        Map<String, TeamData> wanted = new HashMap<>();
        if (TeamsConfig.DISPLAY_SCOREBOARD_MIRROR.getAsBoolean()) {
            for (TeamData team : TeamManagerImpl.INSTANCE.groups()) {
                wanted.put(scoreboardName(team.id()), team);
            }
        }
        // Mirrored teams whose team has gone (or every one, with the mirror off).
        for (PlayerTeam existing : List.copyOf(scoreboard.getPlayerTeams())) {
            if (existing.getName().startsWith(PREFIX) && !wanted.containsKey(existing.getName())) {
                scoreboard.removePlayerTeam(existing);
            }
        }
        wanted.forEach((name, team) -> mirror(server, scoreboard, name, team));
    }

    private static void mirror(MinecraftServer server, Scoreboard scoreboard, String name, TeamData team) {
        PlayerTeam mirrored = scoreboard.getPlayerTeam(name);
        if (mirrored == null) {
            mirrored = scoreboard.addPlayerTeam(name);
        }
        // Each setter sends an update to every player, so only change what differs.
        Component displayName = Component.literal(team.name());
        if (!mirrored.getDisplayName().equals(displayName)) {
            mirrored.setDisplayName(displayName);
        }
        Component prefix = TeamsConfig.CHAT_SHOW_TAG.getAsBoolean()
                ? Component.literal("[" + team.shortName() + "] ").withColor(team.color())
                : Component.empty();
        if (!mirrored.getPlayerPrefix().equals(prefix)) {
            mirrored.setPlayerPrefix(prefix);
        }
        Optional<TeamColor> color = TeamsConfig.DISPLAY_NAME_TAG_COLORS.getAsBoolean() ? Optional.of(nearest(team.color())) : Optional.empty();
        if (!Objects.equals(mirrored.getColor(), color)) {
            mirrored.setColor(color);
        }

        // Scoreboard teams hold player names; offline members are kept too, so their team shows when they join.
        Set<String> members = new HashSet<>();
        for (UUID member : team.members().keySet()) {
            Profiles.nameOf(server, member).ifPresent(members::add);
        }
        for (String player : Set.copyOf(mirrored.getPlayers())) {
            if (!members.contains(player)) {
                scoreboard.removePlayerFromTeam(player, mirrored);
            }
        }
        for (String player : members) {
            if (scoreboard.getPlayersTeam(player) != mirrored) {
                scoreboard.addPlayerToTeam(player, mirrored);
            }
        }
    }

    // The vanilla team colour closest to an RGB colour (scoreboard teams can only colour names in these 16).
    public static TeamColor nearest(int rgb) {
        TeamColor best = TeamColor.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (TeamColor candidate : TeamColor.VALUES) {
            int other = candidate.rgb();
            long dr = ((rgb >> 16) & 0xFF) - ((other >> 16) & 0xFF);
            long dg = ((rgb >> 8) & 0xFF) - ((other >> 8) & 0xFF);
            long db = (rgb & 0xFF) - (other & 0xFF);
            // Weighted for how the eye sees colour, so a pink lands on light purple rather than grey.
            long distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
