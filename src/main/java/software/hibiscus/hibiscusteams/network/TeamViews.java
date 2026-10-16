/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;

// What clients are told about teams, as plain records with stream codecs: a summary of every party and server team
// (for pickers and name colours), the full details of the player's own team, their pending invites, and the server
// settings the GUI needs. Records compare by value, so the sync only resends what changed.
public final class TeamViews {
    private TeamViews() {}

    private static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(Class<E> type) {
        E[] values = type.getEnumConstants();
        return ByteBufCodecs.VAR_INT.map(index -> values[Math.floorMod(index, values.length)], Enum::ordinal);
    }

    static final StreamCodec<ByteBuf, TeamType> TYPE_CODEC = enumCodec(TeamType.class);
    static final StreamCodec<ByteBuf, TeamRank> RANK_CODEC = enumCodec(TeamRank.class);
    private static final StreamCodec<ByteBuf, List<UUID>> UUIDS = UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list());

    // A party or server team as everyone sees it.
    public record Summary(UUID id, TeamType type, String name, String tag, int color, Optional<Identifier> icon,
            int memberCount, boolean freeToJoin, boolean allowAllyInvites) {
        public static final StreamCodec<ByteBuf, Summary> STREAM_CODEC = StreamCodec.of((buf, value) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, value.id);
            TYPE_CODEC.encode(buf, value.type);
            ByteBufCodecs.STRING_UTF8.encode(buf, value.name);
            ByteBufCodecs.STRING_UTF8.encode(buf, value.tag);
            ByteBufCodecs.INT.encode(buf, value.color);
            ByteBufCodecs.optional(Identifier.STREAM_CODEC).encode(buf, value.icon);
            ByteBufCodecs.VAR_INT.encode(buf, value.memberCount);
            ByteBufCodecs.BOOL.encode(buf, value.freeToJoin);
            ByteBufCodecs.BOOL.encode(buf, value.allowAllyInvites);
        }, buf -> new Summary(UUIDUtil.STREAM_CODEC.decode(buf), TYPE_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.INT.decode(buf), ByteBufCodecs.optional(Identifier.STREAM_CODEC).decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf)));
    }

    // A member of the player's team.
    public record Member(UUID id, String name, TeamRank rank, boolean online) {
        public static final StreamCodec<ByteBuf, Member> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Member::id,
                ByteBufCodecs.STRING_UTF8, Member::name,
                RANK_CODEC, Member::rank,
                ByteBufCodecs.BOOL, Member::online,
                Member::new);
    }

    // A player the team has invited (shown to officers), or the team inviting the player (in their invites).
    public record Pending(UUID id, String name, long expires) {
        public static final StreamCodec<ByteBuf, Pending> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Pending::id,
                ByteBufCodecs.STRING_UTF8, Pending::name,
                ByteBufCodecs.VAR_LONG, Pending::expires,
                Pending::new);
    }

    // The player's own team in full, with what the server says they may do (for greying out controls only; every
    // request is checked again on the server).
    public record Details(Summary summary, String description, List<Member> members, List<Pending> invites,
            List<UUID> allies, List<UUID> enemies, List<UUID> allyRequestsIn, List<UUID> allyRequestsOut,
            int maxMembers, int memberLimit, boolean friendlyFire, boolean teamChatDefault, boolean locked,
            TeamRank myRank, boolean canEdit, boolean teamChat) {
        public static final StreamCodec<FriendlyByteBuf, Details> STREAM_CODEC = StreamCodec.of((buf, value) -> {
            Summary.STREAM_CODEC.encode(buf, value.summary);
            ByteBufCodecs.STRING_UTF8.encode(buf, value.description);
            Member.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, value.members);
            Pending.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, value.invites);
            UUIDS.encode(buf, value.allies);
            UUIDS.encode(buf, value.enemies);
            UUIDS.encode(buf, value.allyRequestsIn);
            UUIDS.encode(buf, value.allyRequestsOut);
            buf.writeVarInt(value.maxMembers);
            buf.writeVarInt(value.memberLimit);
            buf.writeBoolean(value.friendlyFire);
            buf.writeBoolean(value.teamChatDefault);
            buf.writeBoolean(value.locked);
            RANK_CODEC.encode(buf, value.myRank);
            buf.writeBoolean(value.canEdit);
            buf.writeBoolean(value.teamChat);
        }, buf -> new Details(Summary.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                Member.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf), Pending.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                UUIDS.decode(buf), UUIDS.decode(buf), UUIDS.decode(buf), UUIDS.decode(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                RANK_CODEC.decode(buf), buf.readBoolean(), buf.readBoolean()));

        public TeamType type() {
            return summary.type();
        }

        public Optional<Member> member(UUID player) {
            return members.stream().filter(member -> member.id().equals(player)).findFirst();
        }
    }

    // The server's settings the GUI follows, and whether the player may run server teams.
    public record ServerInfo(boolean partiesEnabled, boolean alliesEnabled, boolean allowFreeToJoin, int nameMaxLength,
            int maxMembersCap, boolean teamChatEnabled, boolean admin) {
        public static final ServerInfo DEFAULT = new ServerInfo(true, true, true, 32, 0, true, false);
        public static final StreamCodec<ByteBuf, ServerInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, ServerInfo::partiesEnabled,
                ByteBufCodecs.BOOL, ServerInfo::alliesEnabled,
                ByteBufCodecs.BOOL, ServerInfo::allowFreeToJoin,
                ByteBufCodecs.VAR_INT, ServerInfo::nameMaxLength,
                ByteBufCodecs.VAR_INT, ServerInfo::maxMembersCap,
                ByteBufCodecs.BOOL, ServerInfo::teamChatEnabled,
                ByteBufCodecs.BOOL, ServerInfo::admin,
                ServerInfo::new);
    }

    // Everything one player sees: the summaries are shared, the rest is theirs.
    public record PlayerView(Details mine, List<Pending> invites, ServerInfo info) {
        public static final StreamCodec<FriendlyByteBuf, PlayerView> STREAM_CODEC = StreamCodec.composite(
                Details.STREAM_CODEC, PlayerView::mine,
                Pending.STREAM_CODEC.apply(ByteBufCodecs.list()), PlayerView::invites,
                ServerInfo.STREAM_CODEC, PlayerView::info,
                PlayerView::new);
    }

    static List<UUID> copy(java.util.Collection<UUID> ids) {
        return new ArrayList<>(ids);
    }
}
