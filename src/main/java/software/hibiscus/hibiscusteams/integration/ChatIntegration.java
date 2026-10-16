/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.integration;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.team.TeamChat;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamOps;

// Chat: sends a member's chat to their team when that's their default (a leading ! sends it to everyone instead),
// and puts their [TAG] before their name. With the scoreboard mirror on, the mirrored team's prefix shows the tag
// (in chat, the player list and above heads); with it off, the tag goes on the player's display name, here.
public final class ChatIntegration {
    // Shouts past team chat: "!hello" goes to everyone as "hello".
    public static final String PUBLIC_PREFIX = "!";
    private static final int REFRESH_TICKS = 100;

    private static boolean dirty = true;
    private static long tick;

    private ChatIntegration() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ChatIntegration::onChat);
        NeoForge.EVENT_BUS.addListener(ChatIntegration::onNameFormat);
        TeamManagerImpl.INSTANCE.addChangeListener(team -> dirty = true);
    }

    static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!TeamManagerImpl.INSTANCE.isRunning() || !TeamsConfig.CHAT_TEAM_CHAT_ENABLED.getAsBoolean()
                || !TeamOps.teamChatByDefault(player.getUUID())) {
            return;
        }
        String text = event.getRawText();
        if (text.startsWith(PUBLIC_PREFIX)) {
            String everyone = text.substring(PUBLIC_PREFIX.length()).strip();
            if (everyone.isEmpty()) {
                event.setCanceled(true);
            } else {
                event.setMessage(Component.literal(everyone));
            }
            return;
        }
        event.setCanceled(true);
        TeamChat.send(player, text);
    }

    // The [TAG] on the display name, when the scoreboard mirror isn't showing it already.
    static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !TeamManagerImpl.INSTANCE.isRunning()) {
            return;
        }
        if (!TeamsConfig.CHAT_SHOW_TAG.getAsBoolean() || TeamsConfig.DISPLAY_SCOREBOARD_MIRROR.getAsBoolean()) {
            return;
        }
        TeamData team = TeamManagerImpl.INSTANCE.teamOf(player);
        if (team.type().isPlayer()) {
            return;
        }
        event.setDisplayname(Component.empty()
                .append(Component.literal("[" + team.shortName() + "] ").withColor(team.color()))
                .append(event.getDisplayname()));
    }

    // Display names are cached, so they're worked out again after any team change (and now and then, for config edits).
    public static void tick(MinecraftServer server) {
        if (!TeamManagerImpl.INSTANCE.isRunning()) {
            return;
        }
        if (!dirty && ++tick % REFRESH_TICKS != 0) {
            return;
        }
        dirty = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.refreshDisplayName();
        }
    }

    public static void markDirty() {
        dirty = true;
    }
}
