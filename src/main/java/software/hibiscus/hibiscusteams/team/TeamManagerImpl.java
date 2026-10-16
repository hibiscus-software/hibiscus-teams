/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import software.hibiscus.hibiscuslib.api.team.Team;
import software.hibiscus.hibiscuslib.api.team.TeamManager;
import software.hibiscus.hibiscuslib.api.team.TeamRank;
import software.hibiscus.hibiscuslib.api.team.TeamType;
import software.hibiscus.hibiscuslib.api.team.event.TeamCreatedEvent;
import software.hibiscus.hibiscuslib.api.team.event.TeamPropertiesChangedEvent;
import software.hibiscus.hibiscuslib.team.SoloTeamStore;
import software.hibiscus.hibiscuslib.util.Profiles;
import software.hibiscus.hibiscusteams.HibiscusTeams;

// Hibiscus Teams' TeamManager, installed as the Teams API from the mod constructor. Holds the running server's teams,
// which player is in which party, and what needs saving; TeamOps makes the changes (with their checks and events).
// Only usable on the server thread while a server is running.
public final class TeamManagerImpl implements TeamManager {
    public static final TeamManagerImpl INSTANCE = new TeamManagerImpl();

    // Save this many ticks after the last change, or at the latest this long after the first unsaved one.
    private static final int SAVE_QUIET_TICKS = 40;
    private static final int SAVE_MAX_DELAY_TICKS = 600;
    // How often lapsed invites and ally requests are cleared out.
    private static final int PRUNE_INTERVAL_TICKS = 1200;

    private @Nullable MinecraftServer server;
    private @Nullable TeamStorage storage;
    private final Map<UUID, TeamData> teams = new LinkedHashMap<>();
    // Player UUID to the party or server team they're in; players in neither are on their player team.
    private final Map<UUID, UUID> partyOf = new HashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<UUID> deletedFiles = new LinkedHashSet<>();
    private long tick;
    private long firstDirtyTick = -1;
    private long lastDirtyTick = -1;
    // Told about every change to a team (and its removal), after the change: the client sync and scoreboard mirror.
    private final List<Consumer<TeamData>> changeListeners = new CopyOnWriteArrayList<>();

    private TeamManagerImpl() {}

    public boolean isRunning() {
        return server != null;
    }

    public MinecraftServer server() {
        MinecraftServer running = server;
        if (running == null) {
            throw new IllegalStateException("Hibiscus Teams needs a running server");
        }
        return running;
    }

    // ---- Lifecycle ----

    // Loads the world's teams (copying the Hibiscus Lib fallback's data the first time), as the server starts.
    public void start(MinecraftServer server) {
        clear();
        this.server = server;
        TeamStorage storage = new TeamStorage(server);
        this.storage = storage;
        boolean firstRun = !storage.exists();
        teams.putAll(storage.loadAll());
        repair();
        if (firstRun) {
            int copied = migrate(SoloTeamStore.get(server).entries());
            try {
                storage.createDirectory();
            } catch (IOException e) {
                HibiscusTeams.LOGGER.error("Couldn't create {}", storage.directory(), e);
            }
            HibiscusTeams.LOGGER.info("First run in this world: copied {} player team(s) from Hibiscus Lib's solo teams", copied);
            saveNow();
        }
        pruneExpired();
        HibiscusTeams.LOGGER.info("Loaded {} team(s)", teams.size());
    }

    // Saves everything and forgets the server, as it stops.
    public void stop() {
        if (server != null) {
            saveNow();
        }
        clear();
    }

    // Saves, then loads every team from disk again (picking up hand edits). Teams held by other mods go stale.
    public void reload() {
        MinecraftServer running = server();
        saveNow();
        start(running);
        changeListeners.forEach(listener -> teams.values().forEach(listener));
    }

    private void clear() {
        server = null;
        storage = null;
        teams.clear();
        partyOf.clear();
        dirty.clear();
        deletedFiles.clear();
        firstDirtyTick = -1;
        lastDirtyTick = -1;
    }

    // Rebuilds the player-to-party index and fixes what hand edits can break: a player in two teams, a party with no
    // owner or several, a server team with an owner.
    private void repair() {
        partyOf.clear();
        for (TeamData team : teams.values()) {
            if (team.type().isPlayer()) {
                continue;
            }
            for (UUID member : List.copyOf(team.members().keySet())) {
                UUID existing = partyOf.putIfAbsent(member, team.id());
                if (existing != null) {
                    HibiscusTeams.LOGGER.warn("Player {} is in both {} and {}; keeping the first", member, existing, team.id());
                    team.removeMember(member);
                    markDirty(team);
                }
            }
            List<UUID> owners = team.members().entrySet().stream()
                    .filter(entry -> entry.getValue() == TeamRank.OWNER).map(Map.Entry::getKey).toList();
            boolean fixed = false;
            if (team.type() == TeamType.SERVER) {
                owners.forEach(owner -> team.putMember(owner, TeamRank.OFFICER));
                fixed = !owners.isEmpty();
            } else if (owners.size() > 1) {
                owners.subList(1, owners.size()).forEach(extra -> team.putMember(extra, TeamRank.OFFICER));
                fixed = true;
            } else if (owners.isEmpty() && !team.members().isEmpty()) {
                team.putMember(successor(team, null).orElseThrow(), TeamRank.OWNER);
                fixed = true;
            }
            if (fixed) {
                HibiscusTeams.LOGGER.warn("Fixed the owner of team {} ({})", team.name(), team.id());
                markDirty(team);
            }
        }
    }

    // Copies Hibiscus Lib's solo-team data (names and other mods' extraData) into player teams, keeping anything a
    // player team already has. Returns how many players' data was looked at.
    public int migrate(Map<UUID, SoloTeamStore.Entry> entries) {
        entries.forEach((player, entry) -> {
            TeamData team = teams.get(player);
            if (team == null || !team.type().isPlayer()) {
                team = newPlayerTeam(player, entry.name());
                register(team);
            }
            for (Map.Entry<Identifier, CompoundTag> data : entry.data().entrySet()) {
                team.extraDataMap().putIfAbsent(data.getKey(), data.getValue().copy());
            }
            markDirty(team);
        });
        return entries.size();
    }

    // Called every server tick: saves once changes have settled, and now and then clears out lapsed invites.
    public void tick() {
        tick++;
        if (!dirty.isEmpty() || !deletedFiles.isEmpty()) {
            if (tick - lastDirtyTick >= SAVE_QUIET_TICKS || tick - firstDirtyTick >= SAVE_MAX_DELAY_TICKS) {
                saveNow();
            }
        }
        if (tick % PRUNE_INTERVAL_TICKS == 0) {
            pruneExpired();
        }
    }

    // Writes every changed team and removes deleted teams' files.
    public void saveNow() {
        TeamStorage storage = this.storage;
        if (storage == null) {
            return;
        }
        for (UUID id : List.copyOf(dirty)) {
            TeamData team = teams.get(id);
            if (team == null) {
                continue;
            }
            try {
                if (!storage.write(team)) {
                    HibiscusTeams.LOGGER.warn("Not saving team {}: its file didn't load, so it's left as it is", id);
                }
            } catch (IOException e) {
                HibiscusTeams.LOGGER.error("Couldn't save team {} ({})", team.name(), id, e);
            }
        }
        for (UUID id : List.copyOf(deletedFiles)) {
            try {
                storage.delete(id);
            } catch (IOException e) {
                HibiscusTeams.LOGGER.error("Couldn't delete the file of team {}", id, e);
            }
        }
        dirty.clear();
        deletedFiles.clear();
        firstDirtyTick = -1;
        lastDirtyTick = -1;
    }

    void pruneExpired() {
        long now = System.currentTimeMillis();
        for (TeamData team : teams.values()) {
            if (team.pruneExpired(now)) {
                changed(team);
            }
        }
    }

    // The storage, for tests that read a team's file back.
    TeamStorage storage() {
        TeamStorage current = storage;
        if (current == null) {
            throw new IllegalStateException("Hibiscus Teams needs a running server");
        }
        return current;
    }

    // ---- TeamManager ----

    @Override
    public TeamData teamOf(UUID player) {
        server();
        UUID party = partyOf.get(player);
        if (party != null) {
            TeamData team = teams.get(party);
            if (team != null) {
                return team;
            }
        }
        return playerTeam(player);
    }

    @Override
    public TeamData teamOf(net.minecraft.world.entity.player.Player player) {
        return teamOf(player.getUUID());
    }

    // The player's own team, made (and announced with TeamCreatedEvent) the first time anyone asks for it.
    @Override
    public TeamData playerTeam(UUID player) {
        MinecraftServer running = server();
        TeamData team = teams.get(player);
        if (team != null && team.type().isPlayer()) {
            return team;
        }
        String name = Profiles.nameOf(running, player).orElse(player.toString().substring(0, 8));
        team = newPlayerTeam(player, name);
        register(team);
        markDirty(team);
        NeoForge.EVENT_BUS.post(new TeamCreatedEvent(team, null));
        notifyChanged(team);
        return team;
    }

    @Override
    public Optional<Team> team(UUID teamId) {
        server();
        return Optional.ofNullable(teams.get(teamId));
    }

    public Optional<TeamData> teamData(UUID teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    @Override
    public Collection<TeamData> allTeams() {
        server();
        return List.copyOf(teams.values());
    }

    // Parties and server teams, in creation order.
    public List<TeamData> groups() {
        List<TeamData> groups = new ArrayList<>();
        for (TeamData team : teams.values()) {
            if (!team.type().isPlayer()) {
                groups.add(team);
            }
        }
        return groups;
    }

    // A party or server team by name, ignoring case.
    public Optional<TeamData> byName(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (TeamData team : teams.values()) {
            if (!team.type().isPlayer() && team.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(team);
            }
        }
        return Optional.empty();
    }

    // Records a login: makes the player's team on their first join, and keeps its name current.
    public void onLogin(ServerPlayer player) {
        TeamData team = playerTeam(player.getUUID());
        String name = player.nameAndId().name();
        if (!team.name().equals(name)) {
            team.setName(name);
            team.setTag(name);
            changed(team);
            NeoForge.EVENT_BUS.post(new TeamPropertiesChangedEvent(team,
                    Set.of(TeamPropertiesChangedEvent.Property.DISPLAY_NAME, TeamPropertiesChangedEvent.Property.SHORT_NAME)));
        }
    }

    // ---- Changes, for TeamOps ----

    private static TeamData newPlayerTeam(UUID player, String name) {
        TeamData team = new TeamData(player, TeamType.PLAYER, name, name, TeamColors.forId(player));
        team.putMember(player, TeamRank.OWNER);
        team.setCreated(System.currentTimeMillis());
        return team;
    }

    void register(TeamData team) {
        teams.put(team.id(), team);
        if (!team.type().isPlayer()) {
            team.members().keySet().forEach(member -> partyOf.put(member, team.id()));
        }
        deletedFiles.remove(team.id());
    }

    // Removes a team for good: its file goes with the next save.
    void unregister(TeamData team) {
        teams.remove(team.id());
        partyOf.values().removeIf(team.id()::equals);
        dirty.remove(team.id());
        deletedFiles.add(team.id());
        team.markDeleted();
        scheduleSave();
        notifyChanged(team);
    }

    void indexMember(UUID player, @Nullable TeamData team) {
        if (team == null || team.type().isPlayer()) {
            partyOf.remove(player);
        } else {
            partyOf.put(player, team.id());
        }
    }

    public void markDirty(TeamData team) {
        if (teams.get(team.id()) == team) {
            dirty.add(team.id());
            scheduleSave();
        }
    }

    private void scheduleSave() {
        if (firstDirtyTick < 0) {
            firstDirtyTick = tick;
        }
        lastDirtyTick = tick;
    }

    // A team changed in a way others should see: saved, and passed to the change listeners.
    void changed(TeamData team) {
        markDirty(team);
        notifyChanged(team);
    }

    private void notifyChanged(TeamData team) {
        changeListeners.forEach(listener -> listener.accept(team));
    }

    public void addChangeListener(Consumer<TeamData> listener) {
        changeListeners.add(listener);
    }

    // Who takes over a party when its owner goes: the first officer to have joined, else the first member.
    static Optional<UUID> successor(TeamData team, @Nullable UUID leaving) {
        UUID firstMember = null;
        for (Map.Entry<UUID, TeamRank> entry : team.members().entrySet()) {
            if (entry.getKey().equals(leaving) || entry.getValue() == TeamRank.OWNER) {
                continue;
            }
            if (entry.getValue() == TeamRank.OFFICER) {
                return Optional.of(entry.getKey());
            }
            if (firstMember == null) {
                firstMember = entry.getKey();
            }
        }
        return Optional.ofNullable(firstMember);
    }

    public boolean isDirty(UUID teamId) {
        return dirty.contains(teamId);
    }
}
