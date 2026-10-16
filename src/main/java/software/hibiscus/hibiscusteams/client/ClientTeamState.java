/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import software.hibiscus.hibiscuslib.api.team.ClientTeams;
import software.hibiscus.hibiscuslib.api.team.Team;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.client.gui.HibiscusToasts;
import software.hibiscus.hibiscuslib.client.gui.Icon;
import software.hibiscus.hibiscusteams.HibiscusTeams;
import software.hibiscus.hibiscusteams.client.screen.TeamsScreen;
import software.hibiscus.hibiscusteams.network.TeamPayloads;
import software.hibiscus.hibiscusteams.network.TeamViews;
import software.hibiscus.hibiscusteams.team.TeamMessages;

// The client's copy of the teams, kept current by the server's sync: every party and server team's summary, and the
// local player's own view (their team, invites, the server's settings). Feeds ClientTeams, the inventory button's
// badge and the Teams screen, which it refreshes whenever something changes. Main thread only.
public final class ClientTeamState {
    // What comes as a toast (when the player has toasts on); everything else is chat only.
    private static final Set<TeamMessages.Kind> TOAST_KINDS = EnumSet.of(TeamMessages.Kind.INVITED, TeamMessages.Kind.JOINED,
            TeamMessages.Kind.KICKED, TeamMessages.Kind.ALLY_REQUEST, TeamMessages.Kind.OWNERSHIP, TeamMessages.Kind.DISBANDED);
    public static final Icon ICON = Icon.sprite(HibiscusTeams.id("button/teams"));

    private static final Map<UUID, TeamViews.Summary> SUMMARIES = new LinkedHashMap<>();
    private static TeamViews.@Nullable PlayerView view;

    public static final ClientTeams.Provider PROVIDER = new ClientTeams.Provider() {
        @Override
        public Optional<Team> myTeam() {
            return view().map(current -> new ClientTeam(current.mine().summary(), current.mine()));
        }

        @Override
        public Optional<Team> team(UUID teamId) {
            Optional<TeamViews.PlayerView> current = view();
            if (current.isPresent() && current.get().mine().summary().id().equals(teamId)) {
                return myTeam();
            }
            return Optional.ofNullable(SUMMARIES.get(teamId)).map(summary -> new ClientTeam(summary, null));
        }

        @Override
        public Collection<? extends Team> knownTeams() {
            List<Team> teams = new ArrayList<>();
            myTeam().ifPresent(teams::add);
            UUID mine = view().map(current -> current.mine().summary().id()).orElse(null);
            SUMMARIES.values().stream().filter(summary -> !summary.id().equals(mine)).forEach(summary -> teams.add(new ClientTeam(summary, null)));
            return List.copyOf(teams);
        }
    };

    private ClientTeamState() {}

    // ---- From the server ----

    public static void applySummaries(TeamPayloads.Summaries payload) {
        if (payload.replace()) {
            SUMMARIES.clear();
        }
        payload.upserts().forEach(summary -> SUMMARIES.put(summary.id(), summary));
        payload.removed().forEach(SUMMARIES::remove);
        changed();
    }

    public static void applyView(TeamViews.PlayerView newView) {
        view = newView;
        changed();
    }

    // Team news: in chat and/or as a toast, as the player's config says.
    public static void notify(TeamMessages.Kind kind, Component message, Component toast) {
        Minecraft minecraft = Minecraft.getInstance();
        if (TeamsClientConfig.TOASTS.getAsBoolean() && TOAST_KINDS.contains(kind)) {
            HibiscusToasts.show(Component.translatable("hibiscusteams.toast." + kind.name().toLowerCase(java.util.Locale.ROOT)), toast, ICON);
        }
        if (TeamsClientConfig.CHAT.getAsBoolean()) {
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
        }
    }

    // The answer to a request from the Teams screen; shown there, or in chat if the screen has closed.
    public static void result(boolean ok, Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof TeamsScreen screen) {
            screen.showResult(ok, message);
        } else if (!ok) {
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
        }
    }

    // Left the server: forget everything.
    public static void clear() {
        SUMMARIES.clear();
        view = null;
    }

    private static void changed() {
        if (Minecraft.getInstance().gui.screen() instanceof TeamsScreen screen) {
            screen.refresh();
        }
    }

    // ---- For the GUI ----

    // Whether the server runs Hibiscus Teams (and has sent the player's view).
    public static boolean isConnected() {
        return view != null;
    }

    public static Optional<TeamViews.PlayerView> view() {
        return Optional.ofNullable(view);
    }

    public static Collection<TeamViews.Summary> summaries() {
        return List.copyOf(SUMMARIES.values());
    }

    public static Optional<TeamViews.Summary> summary(UUID teamId) {
        if (view != null && view.mine().summary().id().equals(teamId)) {
            return Optional.of(view.mine().summary());
        }
        return Optional.ofNullable(SUMMARIES.get(teamId));
    }

    // The inventory button's badge: invites waiting for the player, and ally requests if they can answer them.
    public static int badgeCount() {
        if (view == null) {
            return 0;
        }
        int count = view.invites().size();
        if (view.mine().myRank().isAtLeast(TeamRank.OFFICER)) {
            count += view.mine().allyRequestsIn().size();
        }
        return count;
    }
}
