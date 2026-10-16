/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// The team chat channel: a message only the sender's party or server team sees, with its own prefix so it can't be
// mistaken for public chat. Sent as system messages, so it works with vanilla chat settings and needs no signing.
public final class TeamChat {
    private TeamChat() {}

    // Sends a message to the sender's team; throws if they're on their own.
    public static void send(ServerPlayer sender, String text) {
        TeamData team = TeamManagerImpl.INSTANCE.teamOf(sender);
        if (team.type().isPlayer()) {
            throw TeamException.of("not_in_party");
        }
        MinecraftServer server = sender.level().getServer();
        Component prefix = Component.translatable("hibiscusteams.chat.prefix", Component.literal(team.shortName()).withColor(team.color()))
                .withStyle(ChatFormatting.GRAY);
        Component message = Component.translatable("hibiscusteams.chat.message", prefix, sender.getDisplayName(), Component.literal(text));
        for (ServerPlayer member : team.onlineMembers(server)) {
            member.sendSystemMessage(message);
        }
        server.sendSystemMessage(message);
    }
}
