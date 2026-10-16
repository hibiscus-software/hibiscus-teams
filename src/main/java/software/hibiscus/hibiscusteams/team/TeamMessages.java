/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.util.Profiles;

// Building and sending the messages players see about teams: names that show details on hover and open /teams info
// on click, [Accept]-style buttons, and notifications to a player, a team or its officers.
public final class TeamMessages {
    // What a notification is about, so the client can choose how to show it (chat, toast or both).
    public enum Kind {
        INVITED,
        JOINED,
        LEFT,
        KICKED,
        ALLY_REQUEST,
        ALLIES,
        OWNERSHIP,
        RANK,
        DISBANDED,
        INFO
    }

    // Where notifications go; the client sync replaces it to also show toasts. `toast` is the message shortened for a
    // toast's one line (no [Accept] buttons, which can't be clicked there).
    public interface Sink {
        void send(ServerPlayer player, Kind kind, Component message, Component toast);
    }

    private static volatile Sink sink = (player, kind, message, toast) -> player.sendSystemMessage(message);

    private TeamMessages() {}

    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    // ---- Pieces ----

    // A team argument as a command expects it: quoted when it has spaces or other characters a word can't.
    public static String quote(String name) {
        return StringArgumentType.escapeIfRequired(name);
    }

    public static String command(String rest) {
        return "/teams " + rest;
    }

    // The team's coloured name; hover for its tag, type and size, click to look it up.
    public static Component team(TeamData team) {
        Component hover = Component.translatable("hibiscusteams.message.team_hover",
                team.shortName(), Component.translatable("hibiscusteams.type." + team.type().name().toLowerCase(java.util.Locale.ROOT)),
                team.members().size());
        String info = command("info " + quote(team.name()));
        return team.displayName().copy().withStyle(style -> style
                .withHoverEvent(new HoverEvent.ShowText(hover))
                .withClickEvent(new ClickEvent.RunCommand(info)));
    }

    public static Component player(MinecraftServer server, UUID player) {
        return Profiles.displayName(server, player);
    }

    public static Component rank(TeamRank rank) {
        return Component.translatable("hibiscusteams.rank." + rank.name().toLowerCase(java.util.Locale.ROOT));
    }

    // [Label] that runs a command when clicked.
    public static Component button(String labelKey, ChatFormatting color, String command, @Nullable Component hover) {
        MutableComponent label = ComponentUtils.wrapInSquareBrackets(Component.translatable(labelKey)).withStyle(color);
        return label.withStyle(style -> {
            style = style.withClickEvent(new ClickEvent.RunCommand(command));
            return hover != null ? style.withHoverEvent(new HoverEvent.ShowText(hover)) : style;
        });
    }

    // ---- Sending ----

    public static void send(MinecraftServer server, UUID player, Kind kind, Component message) {
        send(server, player, kind, message, message);
    }

    public static void send(MinecraftServer server, UUID player, Kind kind, Component message, Component toast) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            sink.send(online, kind, message, toast);
        }
    }

    // To the team's online members, except one player (usually whoever caused it, who gets their own message).
    public static void toTeam(MinecraftServer server, TeamData team, Kind kind, Component message, @Nullable UUID except) {
        for (ServerPlayer member : team.onlineMembers(server)) {
            if (!member.getUUID().equals(except)) {
                sink.send(member, kind, message, message);
            }
        }
    }

    // To the online owner and officers, who can act on invites and ally requests.
    public static void toOfficers(MinecraftServer server, TeamData team, Kind kind, Component message) {
        toOfficers(server, team, kind, message, message);
    }

    public static void toOfficers(MinecraftServer server, TeamData team, Kind kind, Component message, Component toast) {
        for (ServerPlayer member : team.onlineMembers(server)) {
            if (team.memberRank(member.getUUID()).isAtLeast(TeamRank.OFFICER)) {
                sink.send(member, kind, message, toast);
            }
        }
    }
}
