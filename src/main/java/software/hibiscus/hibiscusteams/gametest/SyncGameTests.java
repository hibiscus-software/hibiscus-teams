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

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamSync;
import software.hibiscus.hibiscusteams.network.TeamViews;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamMessages;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// What clients are sent: each player's view of their team (with invites only for those who manage them, and the
// edit hint matching their rank), and that every payload survives encoding and decoding unchanged.
final class SyncGameTests {
    private SyncGameTests() {}

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    @SuppressWarnings("removal")
    private static ServerPlayer join(GameTestHelper helper, List<ServerPlayer> players) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        players.add(player);
        return player;
    }

    static void viewFollowsRank(GameTestHelper helper) {
        List<ServerPlayer> players = new ArrayList<>();
        TeamData party = null;
        try {
            ServerPlayer owner = join(helper, players);
            ServerPlayer member = join(helper, players);
            ServerPlayer invitee = join(helper, players);
            party = TeamOps.createParty(owner.getUUID(), "Sync " + UUID.randomUUID().toString().substring(0, 6));
            TeamOps.invite(Actor.player(owner.getUUID()), member.getUUID());
            TeamOps.accept(member.getUUID(), party);
            TeamOps.invite(Actor.player(owner.getUUID()), invitee.getUUID());
            TeamOps.set(Actor.player(owner.getUUID()), party, TeamSetting.DESCRIPTION, "hello");

            TeamViews.PlayerView ownerView = TeamSync.viewFor(owner);
            TeamViews.Details details = ownerView.mine();
            check(helper, details.summary().id().equals(party.id()) && details.type() == TeamType.PARTY, "the owner sees their party");
            check(helper, details.myRank() == TeamRank.OWNER && details.canEdit(), "the owner may edit");
            check(helper, details.members().size() == 2 && details.member(member.getUUID()).map(TeamViews.Member::online).orElse(false),
                    "both members are listed, online");
            check(helper, details.invites().size() == 1 && details.invites().getFirst().id().equals(invitee.getUUID()),
                    "the owner sees the pending invite");
            check(helper, details.description().equals("hello"), "the description is sent");

            TeamViews.Details memberDetails = TeamSync.viewFor(member).mine();
            check(helper, memberDetails.myRank() == TeamRank.MEMBER && !memberDetails.canEdit(), "a member may not edit");
            check(helper, memberDetails.invites().isEmpty(), "a member isn't shown the party's invites");

            TeamViews.PlayerView inviteeView = TeamSync.viewFor(invitee);
            check(helper, inviteeView.mine().type().isPlayer(), "the invitee is still on their own team");
            check(helper, inviteeView.invites().size() == 1 && inviteeView.invites().getFirst().id().equals(party.id()),
                    "the invitee sees their invite");
            helper.succeed();
        } finally {
            if (party != null && !party.isDeleted()) {
                TeamOps.disband(Actor.admin(null), party);
            }
            players.forEach(player -> helper.getLevel().getServer().getPlayerList().remove(player));
        }
    }

    private static <T> T roundTrip(GameTestHelper helper, StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            codec.encode(buf, value);
            T decoded = codec.decode(buf);
            check(helper, decoded.equals(value), "payload changed on the way: " + value + " became " + decoded);
            check(helper, buf.readableBytes() == 0, "payload left bytes unread: " + value);
            return decoded;
        } finally {
            buf.release();
        }
    }

    static void payloadsRoundTrip(GameTestHelper helper) {
        List<ServerPlayer> players = new ArrayList<>();
        try {
            ServerPlayer player = join(helper, players);
            UUID id = UUID.randomUUID();
            roundTrip(helper, TeamPayloads.View.STREAM_CODEC, new TeamPayloads.View(TeamSync.viewFor(player)));
            TeamViews.Summary summary = new TeamViews.Summary(id, TeamType.SERVER, "Spawn Crew", "SPAWN", 0x3AB7E8,
                    Optional.of(net.minecraft.resources.Identifier.withDefaultNamespace("compass")), 3, true, false);
            roundTrip(helper, TeamPayloads.Summaries.STREAM_CODEC, new TeamPayloads.Summaries(true, List.of(summary), List.of(UUID.randomUUID())));
            roundTrip(helper, TeamPayloads.Notify.STREAM_CODEC, new TeamPayloads.Notify(TeamMessages.Kind.ALLY_REQUEST, Component.literal("hi [Accept]"), Component.literal("hi")));
            roundTrip(helper, TeamPayloads.Result.STREAM_CODEC, new TeamPayloads.Result(false, Component.translatable("hibiscusteams.error.slow_down")));
            roundTrip(helper, TeamPayloads.Request.STREAM_CODEC, new TeamPayloads.Request(TeamPayloads.Action.INVITE_NAME, Optional.empty(), "Alice"));
            roundTrip(helper, TeamPayloads.Request.STREAM_CODEC, TeamPayloads.Request.of(TeamPayloads.Action.KICK, id));
            roundTrip(helper, TeamPayloads.CreateParty.STREAM_CODEC, new TeamPayloads.CreateParty("Red Team", 0xF2557A, Optional.empty(), true));
            roundTrip(helper, TeamPayloads.Settings.STREAM_CODEC, new TeamPayloads.Settings(id,
                    List.of(new TeamPayloads.Setting("tag", "RED"), new TeamPayloads.Setting("free_to_join", "true"))));
            // An invite that never lapses (Long.MAX_VALUE) must survive the variable-length encoding.
            roundTrip(helper, TeamViews.Pending.STREAM_CODEC, new TeamViews.Pending(id, "Bob", Long.MAX_VALUE));
            helper.succeed();
        } finally {
            players.forEach(player -> helper.getLevel().getServer().getPlayerList().remove(player));
        }
    }
}
