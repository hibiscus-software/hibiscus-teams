/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.network;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import software.hibiscus.hibiscusteams.HibiscusTeams;
import software.hibiscus.hibiscusteams.team.TeamMessages;

// Every payload Hibiscus Teams sends. Server to client: team summaries, the player's own view, notifications and the
// answer to a GUI request. Client to server: GUI requests, which the server checks like commands (TeamNetwork).
public final class TeamPayloads {
    private TeamPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(HibiscusTeams.id(path));
    }

    private static final StreamCodec<ByteBuf, List<UUID>> UUIDS = UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list());

    // ---- Server to client ----

    // Parties and server teams that appeared or changed, and ids that went. `replace` clears the client's list first
    // (on login).
    public record Summaries(boolean replace, List<TeamViews.Summary> upserts, List<UUID> removed) implements CustomPacketPayload {
        public static final Type<Summaries> TYPE = payloadType("summaries");
        public static final StreamCodec<ByteBuf, Summaries> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Summaries::replace,
                TeamViews.Summary.STREAM_CODEC.apply(ByteBufCodecs.list()), Summaries::upserts,
                UUIDS, Summaries::removed,
                Summaries::new);

        @Override
        public Type<Summaries> type() {
            return TYPE;
        }
    }

    // The player's own team, invites and the server's settings.
    public record View(TeamViews.PlayerView view) implements CustomPacketPayload {
        public static final Type<View> TYPE = payloadType("view");
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC =
                TeamViews.PlayerView.STREAM_CODEC.map(View::new, View::view).cast();

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    // Something happened to the player's team; the client shows `message` in chat and/or `toast` (the same news
    // without its buttons) as a toast, as its settings say.
    public record Notify(TeamMessages.Kind kind, Component message, Component toast) implements CustomPacketPayload {
        public static final Type<Notify> TYPE = payloadType("notify");
        public static final StreamCodec<RegistryFriendlyByteBuf, Notify> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(index -> TeamMessages.Kind.values()[Math.floorMod(index, TeamMessages.Kind.values().length)], Enum::ordinal),
                Notify::kind,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notify::message,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Notify::toast,
                Notify::new);

        @Override
        public Type<Notify> type() {
            return TYPE;
        }
    }

    // The answer to a GUI request: its message, and whether it went through.
    public record Result(boolean ok, Component message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = payloadType("result");
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Result::ok,
                ComponentSerialization.TRUSTED_STREAM_CODEC, Result::message,
                Result::new);

        @Override
        public Type<Result> type() {
            return TYPE;
        }
    }

    // ---- Client to server ----

    // What a GUI request asks for. The target is a player or team id, as the action needs; the text is a name.
    public enum Action {
        INVITE,
        INVITE_NAME,
        ACCEPT,
        DECLINE,
        JOIN,
        LEAVE,
        KICK,
        PROMOTE,
        DEMOTE,
        TRANSFER,
        DISBAND,
        ALLY_REQUEST,
        ALLY_ACCEPT,
        ALLY_DECLINE,
        ALLY_REMOVE,
        ENEMY_ADD,
        ENEMY_REMOVE,
        TEAM_CHAT
    }

    public record Request(Action action, Optional<UUID> target, String text) implements CustomPacketPayload {
        public static final Type<Request> TYPE = payloadType("request");
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(index -> Action.values()[Math.floorMod(index, Action.values().length)], Enum::ordinal),
                Request::action,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Request::target,
                ByteBufCodecs.stringUtf8(64), Request::text,
                Request::new);

        public static Request of(Action action) {
            return new Request(action, Optional.empty(), "");
        }

        public static Request of(Action action, UUID target) {
            return new Request(action, Optional.of(target), "");
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    // The create-party dialog: the name, and the settings to start with.
    public record CreateParty(String name, int color, Optional<Identifier> icon, boolean freeToJoin) implements CustomPacketPayload {
        public static final Type<CreateParty> TYPE = payloadType("create_party");
        public static final StreamCodec<ByteBuf, CreateParty> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), CreateParty::name,
                ByteBufCodecs.INT, CreateParty::color,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), CreateParty::icon,
                ByteBufCodecs.BOOL, CreateParty::freeToJoin,
                CreateParty::new);

        @Override
        public Type<CreateParty> type() {
            return TYPE;
        }
    }

    // One setting to change.
    public record Setting(String key, String value) {
        public static final StreamCodec<ByteBuf, Setting> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Setting::key,
                ByteBufCodecs.stringUtf8(300), Setting::value,
                Setting::new);
    }

    // The settings tab's Save: every setting that was changed, applied in order.
    public record Settings(UUID team, List<Setting> settings) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = payloadType("settings");
        public static final StreamCodec<ByteBuf, Settings> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Settings::team,
                Setting.STREAM_CODEC.apply(ByteBufCodecs.list(16)), Settings::settings,
                Settings::new);

        @Override
        public Type<Settings> type() {
            return TYPE;
        }
    }
}
