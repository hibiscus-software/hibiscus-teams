/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import software.hibiscus.hibiscuslib.api.team.Team;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscusteams.network.TeamViews;

// A team as the client knows it, for ClientTeams: the summary every player gets, plus the full details when it's the
// local player's own team. Other teams' members aren't sent, so for those members() is empty. Read-only: extraData
// is a throwaway tag and onlineMembers is empty, as the Teams API says for the client.
final class ClientTeam implements Team {
    private final TeamViews.Summary summary;
    private final TeamViews.@Nullable Details details;

    ClientTeam(TeamViews.Summary summary, TeamViews.@Nullable Details details) {
        this.summary = summary;
        this.details = details;
    }

    @Override
    public UUID id() {
        return summary.id();
    }

    @Override
    public TeamType type() {
        return summary.type();
    }

    @Override
    public Component displayName() {
        return Component.literal(summary.name()).withColor(summary.color());
    }

    @Override
    public String shortName() {
        return summary.tag();
    }

    @Override
    public int color() {
        return summary.color();
    }

    @Override
    public Optional<UUID> owner() {
        if (details == null) {
            return Optional.empty();
        }
        return details.members().stream().filter(member -> member.rank() == TeamRank.OWNER).map(TeamViews.Member::id).findFirst();
    }

    @Override
    public Map<UUID, TeamRank> members() {
        Map<UUID, TeamRank> members = new LinkedHashMap<>();
        if (details != null) {
            details.members().forEach(member -> members.put(member.id(), member.rank()));
        }
        return Map.copyOf(members);
    }

    @Override
    public Set<UUID> allies() {
        return details == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(details.allies()));
    }

    @Override
    public TeamRank rankOf(UUID player) {
        if (details == null) {
            return TeamRank.NONE;
        }
        return details.member(player).map(TeamViews.Member::rank)
                .orElseGet(() -> details.invites().stream().anyMatch(invite -> invite.id().equals(player)) ? TeamRank.INVITED : TeamRank.NONE);
    }

    @Override
    public List<ServerPlayer> onlineMembers(MinecraftServer server) {
        return List.of();
    }

    @Override
    public CompoundTag extraData(Identifier owningMod) {
        return new CompoundTag();
    }

    @Override
    public void markDirty() {}

    @Override
    public boolean equals(Object other) {
        return other instanceof ClientTeam team && team.summary.id().equals(summary.id());
    }

    @Override
    public int hashCode() {
        return summary.id().hashCode();
    }
}
