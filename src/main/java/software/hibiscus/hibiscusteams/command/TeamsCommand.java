/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.util.PermissionLevels;
import software.hibiscus.hibiscuslib.util.Profiles;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamChat;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamException;
import software.hibiscus.hibiscusteams.team.TeamExport;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamMessages;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// /hibiscusteams (alias /teams): party, allies, enemies, info, list, msg and chat for players (ranks decide what they
// may do), and server/admin subcommands behind hibiscusteams.admin.* nodes. Each command is a thin wrapper over
// TeamOps, so the checks are the same as the GUI's; a TeamException becomes the command's failure message.
public final class TeamsCommand {
    private static final int PAGE_SIZE = 8;

    // A command body: returns the message for whoever ran it, or null if it sent its own output.
    @FunctionalInterface
    private interface Action {
        @Nullable Component run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    private TeamsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("hibiscusteams"));
        dispatcher.register(build("teams"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return Commands.literal(root)
                .executes(context -> run(context, c -> info(c.getSource(), teams().teamOf(player(c)))))
                .then(party())
                .then(allies())
                .then(enemies())
                .then(Commands.literal("info")
                        .executes(context -> run(context, c -> info(c.getSource(), teams().teamOf(player(c)))))
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .suggests(TEAMS_AND_PLAYERS)
                                .executes(context -> run(context, c -> info(c.getSource(), infoTarget(c, "target"))))))
                .then(Commands.literal("list")
                        .executes(context -> run(context, c -> list(c.getSource(), 1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> run(context, c -> list(c.getSource(), IntegerArgumentType.getInteger(c, "page"))))))
                .then(Commands.literal("msg")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> run(context, c -> {
                                    TeamChat.send(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "message"));
                                    return null;
                                }))))
                .then(Commands.literal("chat")
                        .executes(context -> run(context, c -> Component.translatable(
                                TeamOps.toggleTeamChat(player(c)) ? "hibiscusteams.message.team_chat_on" : "hibiscusteams.message.team_chat_off"))))
                .then(server())
                .then(admin());
    }

    // ---- /teams party ----

    private static LiteralArgumentBuilder<CommandSourceStack> party() {
        return Commands.literal("party")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> run(context, c -> {
                                    TeamData party = TeamOps.createParty(player(c), unquote(StringArgumentType.getString(c, "name")));
                                    return Component.translatable("hibiscusteams.message.created", TeamMessages.team(party));
                                }))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(INVITABLE)
                                .executes(context -> run(context, c -> TeamOps.invite(actor(c), playerArg(c, "player"))))))
                .then(Commands.literal("join")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(JOINABLE)
                                .executes(context -> run(context, c -> TeamOps.joinParty(player(c), teamArg(c, "team"))))))
                .then(Commands.literal("accept")
                        .executes(context -> run(context, c -> TeamOps.accept(player(c), null)))
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(INVITES)
                                .executes(context -> run(context, c -> TeamOps.accept(player(c), teamArg(c, "team"))))))
                .then(Commands.literal("decline")
                        .executes(context -> run(context, c -> TeamOps.decline(player(c), null)))
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(INVITES)
                                .executes(context -> run(context, c -> TeamOps.decline(player(c), teamArg(c, "team"))))))
                .then(Commands.literal("leave")
                        .executes(context -> run(context, c -> TeamOps.leave(player(c)))))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(KICKABLE)
                                .executes(context -> run(context, c -> TeamOps.kick(actor(c), playerArg(c, "player"))))))
                .then(Commands.literal("promote")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(MEMBERS)
                                .executes(context -> run(context, c -> TeamOps.promote(actor(c), playerArg(c, "player"))))))
                .then(Commands.literal("demote")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(MEMBERS)
                                .executes(context -> run(context, c -> TeamOps.demote(actor(c), playerArg(c, "player"))))))
                .then(Commands.literal("transfer_owner")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(MEMBERS)
                                .executes(context -> run(context, c -> TeamOps.transferOwnership(actor(c), playerArg(c, "player"))))))
                .then(settings(Commands.literal("settings"), c -> ownGroup(c), false))
                .then(Commands.literal("disband")
                        .executes(context -> run(context, c -> {
                            TeamData party = ownGroup(c);
                            return Component.translatable("hibiscusteams.message.disband_confirm", TeamMessages.team(party))
                                    .append(" ")
                                    .append(TeamMessages.button("hibiscusteams.button.disband", ChatFormatting.RED,
                                            TeamMessages.command("party disband confirm"), null));
                        }))
                        .then(Commands.literal("confirm")
                                .executes(context -> run(context, c -> TeamOps.disband(actor(c), ownGroup(c))))));
    }

    // [<key> [<value>]] under `node`, for a team picked by `team`: lists, shows or sets a property.
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T settings(T node, TeamPicker team, boolean admin) {
        return node
                .executes(context -> run(context, c -> listSettings(c.getSource(), team.pick(c))))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                Stream.of(TeamSetting.values()).filter(s -> admin || s != TeamSetting.LOCKED).map(TeamSetting::key), builder))
                        .executes(context -> run(context, c -> {
                            TeamSetting setting = settingArg(c);
                            return Component.translatable("hibiscusteams.message.setting_value", setting.key(), setting.get(team.pick(c)));
                        }))
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    Optional<TeamSetting> setting = TeamSetting.byKey(StringArgumentType.getString(context, "key"));
                                    if (setting.isEmpty()) {
                                        return builder.buildFuture();
                                    }
                                    if (setting.get() == TeamSetting.ICON) {
                                        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
                                    }
                                    return SharedSuggestionProvider.suggest(setting.get().suggestions(), builder);
                                })
                                .executes(context -> run(context, c -> {
                                    Actor actor = admin ? Actor.admin(playerOrNull(c)) : actor(c);
                                    return TeamOps.set(actor, team.pick(c), settingArg(c), StringArgumentType.getString(c, "value"));
                                }))));
    }

    @FunctionalInterface
    private interface TeamPicker {
        TeamData pick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException;
    }

    // ---- /teams allies, /teams enemies ----

    private static LiteralArgumentBuilder<CommandSourceStack> allies() {
        return Commands.literal("allies")
                .executes(context -> run(context, c -> listAllies(c.getSource(), ownGroup(c))))
                .then(Commands.literal("list")
                        .executes(context -> run(context, c -> listAllies(c.getSource(), ownGroup(c)))))
                .then(Commands.literal("add")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(OTHER_PARTIES)
                                .executes(context -> run(context, c -> TeamOps.requestAlly(actor(c), teamArg(c, "team"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(ALLIES)
                                .executes(context -> run(context, c -> TeamOps.removeAlly(actor(c), teamArg(c, "team"))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(ALLY_REQUESTS)
                                .executes(context -> run(context, c -> TeamOps.acceptAlly(actor(c), teamArg(c, "team"))))))
                .then(Commands.literal("decline")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(ALLY_REQUESTS)
                                .executes(context -> run(context, c -> TeamOps.declineAlly(actor(c), teamArg(c, "team"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> enemies() {
        return Commands.literal("enemies")
                .executes(context -> run(context, c -> listEnemies(c.getSource(), ownGroup(c))))
                .then(Commands.literal("list")
                        .executes(context -> run(context, c -> listEnemies(c.getSource(), ownGroup(c)))))
                .then(Commands.literal("add")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(OTHER_GROUPS)
                                .executes(context -> run(context, c -> TeamOps.addEnemy(actor(c), teamArg(c, "team"))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(ENEMIES)
                                .executes(context -> run(context, c -> TeamOps.removeEnemy(actor(c), teamArg(c, "team"))))));
    }

    // ---- /teams server, /teams admin (ops) ----

    private static LiteralArgumentBuilder<CommandSourceStack> server() {
        return Commands.literal("server")
                .requires(requires(TeamPermissions.SERVER_TEAMS))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> run(context, c -> {
                                    TeamData team = TeamOps.createServerTeam(playerOrNull(c), unquote(StringArgumentType.getString(c, "name")));
                                    return Component.translatable("hibiscusteams.message.created", TeamMessages.team(team));
                                }))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(SERVER_TEAMS)
                                .executes(context -> run(context, c -> TeamOps.disband(Actor.admin(playerOrNull(c)), serverTeamArg(c, "team"))))))
                .then(Commands.literal("add")
                        .then(Commands.argument("team", StringArgumentType.string())
                                .suggests(SERVER_TEAMS)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(ONLINE_PLAYERS)
                                        .executes(context -> run(context, c -> TeamOps.forceJoin(playerArg(c, "player"), serverTeamArg(c, "team")))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("team", StringArgumentType.string())
                                .suggests(SERVER_TEAMS)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(TEAM_ARG_MEMBERS)
                                        .executes(context -> run(context, c -> TeamOps.removeFrom(serverTeamArg(c, "team"), playerArg(c, "player")))))))
                .then(Commands.literal("settings")
                        .then(settings(Commands.argument("team", StringArgumentType.string()).suggests(SERVER_TEAMS),
                                c -> serverTeamArg(c, "team"), true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("admin")
                .requires(source -> Stream.of(TeamPermissions.MEMBERS, TeamPermissions.RENAME, TeamPermissions.DISBAND,
                        TeamPermissions.RELOAD, TeamPermissions.EXPORT).anyMatch(node -> requires(node).test(source)))
                .then(Commands.literal("force_join")
                        .requires(requires(TeamPermissions.MEMBERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .then(Commands.argument("team", StringArgumentType.greedyString())
                                        .suggests(ALL_GROUPS)
                                        .executes(context -> run(context, c -> TeamOps.forceJoin(playerArg(c, "player"), teamArg(c, "team")))))))
                .then(Commands.literal("force_leave")
                        .requires(requires(TeamPermissions.MEMBERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(ONLINE_PLAYERS)
                                .executes(context -> run(context, c -> TeamOps.forceLeave(playerArg(c, "player"))))))
                .then(Commands.literal("rename")
                        .requires(requires(TeamPermissions.RENAME))
                        .then(Commands.argument("team", StringArgumentType.string())
                                .suggests(ALL_GROUPS)
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> run(context, c -> TeamOps.set(Actor.admin(playerOrNull(c)), teamArg(c, "team"),
                                                TeamSetting.NAME, unquote(StringArgumentType.getString(c, "name"))))))))
                .then(Commands.literal("disband")
                        .requires(requires(TeamPermissions.DISBAND))
                        .then(Commands.argument("team", StringArgumentType.greedyString())
                                .suggests(ALL_GROUPS)
                                .executes(context -> run(context, c -> TeamOps.disband(Actor.admin(playerOrNull(c)), teamArg(c, "team"))))))
                .then(Commands.literal("reload")
                        .requires(requires(TeamPermissions.RELOAD))
                        .executes(context -> run(context, c -> {
                            teams().reload();
                            return Component.translatable("hibiscusteams.message.reloaded", teams().allTeams().size());
                        })))
                .then(Commands.literal("export")
                        .requires(requires(TeamPermissions.EXPORT))
                        .executes(context -> run(context, c -> {
                            try {
                                Path file = TeamExport.export(c.getSource().getServer());
                                return Component.translatable("hibiscusteams.message.exported", file.getFileName().toString());
                            } catch (IOException e) {
                                throw TeamException.of("export_failed", e.getMessage());
                            }
                        })));
    }

    private static java.util.function.Predicate<CommandSourceStack> requires(PermissionNode<Boolean> node) {
        return PermissionLevels.requires(node, PermissionLevel.GAMEMASTERS);
    }

    // ---- Output ----

    // A team's details, a line at a time.
    private static @Nullable Component info(CommandSourceStack source, TeamData team) {
        MinecraftServer server = source.getServer();
        List<Component> lines = new ArrayList<>();
        if (team.type().isPlayer()) {
            UUID player = team.id();
            lines.add(Component.translatable("hibiscusteams.info.solo", TeamMessages.player(server, player)));
            List<TeamData> invites = TeamOps.invitesFor(player);
            if (!invites.isEmpty() && source.getPlayer() != null && source.getPlayer().getUUID().equals(player)) {
                lines.add(Component.translatable("hibiscusteams.info.invites", join(invites.stream().map(TeamMessages::team).toList())));
            }
            send(source, lines);
            return null;
        }
        lines.add(Component.translatable("hibiscusteams.info.title", TeamMessages.team(team),
                Component.literal(team.tag()).withColor(team.color()),
                Component.translatable("hibiscusteams.type." + team.type().name().toLowerCase(Locale.ROOT))).withStyle(ChatFormatting.BOLD));
        if (!team.description().isEmpty()) {
            lines.add(Component.literal(team.description()).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        }
        team.owner().ifPresent(owner -> lines.add(Component.translatable("hibiscusteams.info.owner", member(server, team, owner))));
        int limit = TeamOps.memberLimit(team);
        Component count = limit > 0
                ? Component.translatable("hibiscusteams.info.count_limit", team.members().size(), limit)
                : Component.literal(Integer.toString(team.members().size()));
        List<Component> members = team.members().keySet().stream()
                .filter(id -> team.memberRank(id) != TeamRank.OWNER)
                .map(id -> member(server, team, id)).toList();
        lines.add(Component.translatable("hibiscusteams.info.members", count, members.isEmpty() ? none() : join(members)));
        List<Component> allies = team.allies().stream().flatMap(id -> teams().teamData(id).stream()).map(TeamMessages::team).toList();
        lines.add(Component.translatable("hibiscusteams.info.allies", allies.isEmpty() ? none() : join(allies)));
        lines.add(Component.translatable("hibiscusteams.info.settings",
                yesNo(team.freeToJoin()), yesNo(team.friendlyFire()), yesNo(team.allowAllyInvites())));
        if (team.type() == TeamType.SERVER) {
            lines.add(Component.translatable("hibiscusteams.info.locked", yesNo(team.locked())));
        }
        ServerPlayer viewer = source.getPlayer();
        if (viewer != null && team.type() == TeamType.PARTY && !team.isMember(viewer.getUUID())) {
            String quoted = TeamMessages.quote(team.name());
            if (team.hasInvite(viewer.getUUID(), System.currentTimeMillis())) {
                lines.add(TeamMessages.button("hibiscusteams.button.accept", ChatFormatting.GREEN, TeamMessages.command("party accept " + quoted), null));
            } else if (team.freeToJoin()) {
                lines.add(TeamMessages.button("hibiscusteams.button.join", ChatFormatting.GREEN, TeamMessages.command("party join " + quoted), null));
            }
        }
        send(source, lines);
        return null;
    }

    // A member's name: green when online, with their rank on hover.
    private static Component member(MinecraftServer server, TeamData team, UUID player) {
        boolean online = server.getPlayerList().getPlayer(player) != null;
        Component hover = Component.translatable(online ? "hibiscusteams.info.online" : "hibiscusteams.info.offline",
                TeamMessages.rank(team.memberRank(player)));
        return TeamMessages.player(server, player).copy()
                .withStyle(online ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    // Parties and server teams, a page at a time, each clickable for its details.
    private static @Nullable Component list(CommandSourceStack source, int page) {
        List<TeamData> groups = new ArrayList<>(teams().groups());
        if (groups.isEmpty()) {
            return Component.translatable("hibiscusteams.message.no_teams");
        }
        groups.sort(Comparator.comparing(team -> team.name().toLowerCase(Locale.ROOT)));
        int pages = (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.min(page, pages);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("hibiscusteams.list.header", current, pages).withStyle(ChatFormatting.BOLD));
        for (TeamData team : groups.subList((current - 1) * PAGE_SIZE, Math.min(groups.size(), current * PAGE_SIZE))) {
            MutableComponent line = Component.translatable("hibiscusteams.list.entry", TeamMessages.team(team),
                    Component.literal(team.tag()).withColor(team.color()), team.members().size(),
                    Component.translatable("hibiscusteams.type." + team.type().name().toLowerCase(Locale.ROOT)));
            if (team.type() == TeamType.PARTY && team.freeToJoin()) {
                line.append(" ").append(TeamMessages.button("hibiscusteams.button.join", ChatFormatting.GREEN,
                        TeamMessages.command("party join " + TeamMessages.quote(team.name())), null));
            }
            lines.add(line);
        }
        if (pages > 1) {
            MutableComponent nav = Component.empty();
            if (current > 1) {
                nav.append(pageButton("hibiscusteams.button.previous", current - 1)).append(" ");
            }
            if (current < pages) {
                nav.append(pageButton("hibiscusteams.button.next", current + 1));
            }
            lines.add(nav);
        }
        send(source, lines);
        return null;
    }

    private static Component pageButton(String key, int page) {
        return TeamMessages.button(key, ChatFormatting.AQUA, TeamMessages.command("list " + page), null);
    }

    private static @Nullable Component listSettings(CommandSourceStack source, TeamData team) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("hibiscusteams.settings.header", TeamMessages.team(team)).withStyle(ChatFormatting.BOLD));
        String prefix = team.type() == TeamType.SERVER
                ? "server settings " + TeamMessages.quote(team.name()) + " "
                : "party settings ";
        for (TeamSetting setting : TeamSetting.forType(team.type())) {
            String suggest = TeamMessages.command(prefix + setting.key() + " ");
            lines.add(Component.translatable("hibiscusteams.settings.entry", setting.key(), setting.get(team))
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.SuggestCommand(suggest))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("hibiscusteams.settings.hover." + setting.key())))));
        }
        send(source, lines);
        return null;
    }

    private static @Nullable Component listAllies(CommandSourceStack source, TeamData party) {
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        List<Component> allies = party.allies().stream().flatMap(id -> teams().teamData(id).stream()).map(TeamMessages::team).toList();
        lines.add(Component.translatable("hibiscusteams.info.allies", allies.isEmpty() ? none() : join(allies)));
        for (Map.Entry<UUID, Long> request : party.allyRequests().entrySet()) {
            if (request.getValue() <= now) {
                continue;
            }
            teams().teamData(request.getKey()).ifPresent(from -> {
                String quoted = TeamMessages.quote(from.name());
                lines.add(Component.translatable("hibiscusteams.message.ally_request", TeamMessages.team(from))
                        .append(" ")
                        .append(TeamMessages.button("hibiscusteams.button.accept", ChatFormatting.GREEN, TeamMessages.command("allies accept " + quoted), null))
                        .append(" ")
                        .append(TeamMessages.button("hibiscusteams.button.decline", ChatFormatting.RED, TeamMessages.command("allies decline " + quoted), null)));
            });
        }
        List<Component> outgoing = teams().groups().stream().filter(team -> team.hasAllyRequest(party.id(), now)).map(TeamMessages::team).toList();
        if (!outgoing.isEmpty()) {
            lines.add(Component.translatable("hibiscusteams.info.ally_requests_sent", join(outgoing)));
        }
        send(source, lines);
        return null;
    }

    private static @Nullable Component listEnemies(CommandSourceStack source, TeamData party) {
        List<Component> enemies = party.enemies().stream().flatMap(id -> teams().teamData(id).stream()).map(TeamMessages::team).toList();
        return Component.translatable("hibiscusteams.info.enemies", enemies.isEmpty() ? none() : join(enemies));
    }

    private static void send(CommandSourceStack source, List<Component> lines) {
        lines.forEach(line -> source.sendSuccess(() -> line, false));
    }

    private static Component join(List<Component> parts) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                joined.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            }
            joined.append(parts.get(i));
        }
        return joined;
    }

    private static Component none() {
        return Component.translatable("hibiscusteams.info.none").withStyle(ChatFormatting.GRAY);
    }

    private static Component yesNo(boolean value) {
        return Component.translatable(value ? "hibiscusteams.info.yes" : "hibiscusteams.info.no");
    }

    // ---- Running and arguments ----

    private static int run(CommandContext<CommandSourceStack> context, Action action) throws CommandSyntaxException {
        try {
            Component message = action.run(context);
            if (message != null) {
                context.getSource().sendSuccess(() -> message, false);
            }
            return 1;
        } catch (TeamException e) {
            context.getSource().sendFailure(e.reason());
            return 0;
        }
    }

    private static TeamManagerImpl teams() {
        return TeamManagerImpl.INSTANCE;
    }

    private static UUID player(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getSource().getPlayerOrException().getUUID();
    }

    private static @Nullable UUID playerOrNull(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        return player != null ? player.getUUID() : null;
    }

    private static Actor actor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return Actor.player(player(context));
    }

    // The party or server team the player running the command is in.
    private static TeamData ownGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        TeamData team = teams().teamOf(player(context));
        if (team.type().isPlayer()) {
            throw TeamException.of("not_in_party");
        }
        return team;
    }

    // Greedy arguments take a team name with or without quotes.
    private static String unquote(String raw) {
        String text = raw.strip();
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1).replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
        }
        return text;
    }

    // A party or server team by name (any case) or id.
    private static TeamData teamArg(CommandContext<CommandSourceStack> context, String name) {
        String text = unquote(StringArgumentType.getString(context, name));
        Optional<TeamData> team = teams().byName(text);
        if (team.isEmpty()) {
            try {
                team = teams().teamData(UUID.fromString(text)).filter(found -> !found.type().isPlayer());
            } catch (IllegalArgumentException ignored) {
                // not an id either
            }
        }
        return team.orElseThrow(() -> TeamException.of("unknown_team", text));
    }

    private static TeamData serverTeamArg(CommandContext<CommandSourceStack> context, String name) {
        TeamData team = teamArg(context, name);
        if (team.type() != TeamType.SERVER) {
            throw TeamException.of("not_server_team", TeamMessages.team(team));
        }
        return team;
    }

    // info's target: a team by name, else a player by name (their current team).
    private static TeamData infoTarget(CommandContext<CommandSourceStack> context, String name) {
        String text = unquote(StringArgumentType.getString(context, name));
        Optional<TeamData> team = teams().byName(text);
        if (team.isPresent()) {
            return team.get();
        }
        return Profiles.idOf(context.getSource().getServer(), text).map(teams()::teamOf)
                .orElseThrow(() -> TeamException.of("unknown_team_or_player", text));
    }

    // A player by name, online or not (through the server's name cache; on offline-mode servers any name works).
    private static UUID playerArg(CommandContext<CommandSourceStack> context, String name) {
        String text = StringArgumentType.getString(context, name);
        return Profiles.idOrOffline(context.getSource().getServer(), text)
                .orElseThrow(() -> TeamException.of("unknown_player", text));
    }

    private static TeamSetting settingArg(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "key");
        return TeamSetting.byKey(key).orElseThrow(() -> TeamException.of("unknown_setting", key));
    }

    // ---- Suggestions ----

    private static SuggestionProvider<CommandSourceStack> teamNames(Function<CommandSourceStack, Stream<TeamData>> teams) {
        return (context, builder) -> {
            if (!teams().isRunning()) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(teams.apply(context.getSource()).map(team -> TeamMessages.quote(team.name())), builder);
        };
    }

    private static SuggestionProvider<CommandSourceStack> playerNames(Function<CommandSourceStack, Stream<UUID>> players) {
        return (context, builder) -> {
            if (!teams().isRunning()) {
                return builder.buildFuture();
            }
            MinecraftServer server = context.getSource().getServer();
            return SharedSuggestionProvider.suggest(players.apply(context.getSource())
                    .flatMap(id -> Profiles.nameOf(server, id).stream()), builder);
        };
    }

    private static Optional<TeamData> sourceGroup(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return Optional.empty();
        }
        TeamData team = teams().teamOf(player);
        return team.type().isPlayer() ? Optional.empty() : Optional.of(team);
    }

    private static Stream<TeamData> groupsWhere(java.util.function.Predicate<TeamData> filter) {
        return teams().groups().stream().filter(filter);
    }

    private static final SuggestionProvider<CommandSourceStack> ALL_GROUPS = teamNames(source -> groupsWhere(team -> true));
    private static final SuggestionProvider<CommandSourceStack> SERVER_TEAMS = teamNames(source -> groupsWhere(team -> team.type() == TeamType.SERVER));
    private static final SuggestionProvider<CommandSourceStack> JOINABLE = teamNames(source -> {
        UUID player = source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        long now = System.currentTimeMillis();
        return groupsWhere(team -> team.type() == TeamType.PARTY && (team.freeToJoin() || player != null && team.hasInvite(player, now)));
    });
    private static final SuggestionProvider<CommandSourceStack> INVITES = teamNames(source ->
            source.getPlayer() == null ? Stream.empty() : TeamOps.invitesFor(source.getPlayer().getUUID()).stream());
    private static final SuggestionProvider<CommandSourceStack> OTHER_PARTIES = teamNames(source -> {
        Optional<TeamData> own = sourceGroup(source);
        return groupsWhere(team -> team.type() == TeamType.PARTY && own.map(o -> !o.equals(team) && !o.allies().contains(team.id())).orElse(true));
    });
    private static final SuggestionProvider<CommandSourceStack> OTHER_GROUPS = teamNames(source -> {
        Optional<TeamData> own = sourceGroup(source);
        return groupsWhere(team -> own.map(o -> !o.equals(team)).orElse(true));
    });
    private static final SuggestionProvider<CommandSourceStack> ALLIES = teamNames(source -> sourceGroup(source).stream().flatMap(own -> {
        long now = System.currentTimeMillis();
        return groupsWhere(team -> own.allies().contains(team.id()) || team.hasAllyRequest(own.id(), now));
    }));
    private static final SuggestionProvider<CommandSourceStack> ALLY_REQUESTS = teamNames(source -> sourceGroup(source).stream().flatMap(own -> {
        long now = System.currentTimeMillis();
        return groupsWhere(team -> own.hasAllyRequest(team.id(), now));
    }));
    private static final SuggestionProvider<CommandSourceStack> ENEMIES = teamNames(source ->
            sourceGroup(source).stream().flatMap(own -> groupsWhere(team -> own.enemies().contains(team.id()))));
    private static final SuggestionProvider<CommandSourceStack> TEAMS_AND_PLAYERS = (context, builder) -> {
        if (!teams().isRunning()) {
            return builder.buildFuture();
        }
        Stream<String> names = Stream.concat(teams().groups().stream().map(team -> TeamMessages.quote(team.name())),
                Stream.of(context.getSource().getOnlinePlayerNames().toArray(String[]::new)));
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);
    // Online players who aren't already in the inviter's party.
    private static final SuggestionProvider<CommandSourceStack> INVITABLE = playerNames(source -> {
        Optional<TeamData> own = sourceGroup(source);
        return source.getServer().getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID)
                .filter(id -> own.map(team -> !team.isMember(id)).orElse(true));
    });
    private static final SuggestionProvider<CommandSourceStack> MEMBERS = playerNames(source -> {
        UUID self = source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        return sourceGroup(source).stream().flatMap(team -> team.members().keySet().stream()).filter(id -> !id.equals(self));
    });
    private static final SuggestionProvider<CommandSourceStack> KICKABLE = playerNames(source -> {
        UUID self = source.getPlayer() != null ? source.getPlayer().getUUID() : null;
        return sourceGroup(source).stream().flatMap(team -> Stream.concat(team.members().keySet().stream(), team.invites().keySet().stream()))
                .filter(id -> !id.equals(self));
    });
    private static final SuggestionProvider<CommandSourceStack> TEAM_ARG_MEMBERS = (context, builder) -> {
        if (!teams().isRunning()) {
            return builder.buildFuture();
        }
        MinecraftServer server = context.getSource().getServer();
        Optional<TeamData> team = teams().byName(StringArgumentType.getString(context, "team"));
        return SharedSuggestionProvider.suggest(team.stream().flatMap(found -> found.members().keySet().stream())
                .flatMap(id -> Profiles.nameOf(server, id).stream()), builder);
    };
}
