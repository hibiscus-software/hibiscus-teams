/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import software.hibiscus.hibiscuslib.util.SnbtFiles;
import software.hibiscus.hibiscusteams.HibiscusTeams;

// The teams on disk: <world>/hibiscusteams/<team id>.snbt, one file per team, written atomically with a .bak by
// SnbtFiles. A file that won't load is logged and remembered as broken, and is never written over, so a typo in a
// hand edit can be fixed rather than lost.
public final class TeamStorage {
    private static final String SUFFIX = ".snbt";

    private final Path directory;
    // Teams whose file is there but wouldn't load. Never saved or deleted until the server restarts.
    private final Set<UUID> broken = new HashSet<>();

    TeamStorage(MinecraftServer server) {
        this(server.getWorldPath(LevelResource.ROOT).resolve(HibiscusTeams.MODID));
    }

    public TeamStorage(Path directory) {
        this.directory = directory;
    }

    Path directory() {
        return directory;
    }

    // Whether Hibiscus Teams has ever saved in this world (false the first time it runs, when fallback data is copied).
    boolean exists() {
        return Files.isDirectory(directory);
    }

    public Path file(UUID teamId) {
        return directory.resolve(teamId + SUFFIX);
    }

    public boolean isBroken(UUID teamId) {
        return broken.contains(teamId);
    }

    // Every team that loads; broken files are logged and skipped.
    public Map<UUID, TeamData> loadAll() {
        broken.clear();
        Map<UUID, TeamData> teams = new LinkedHashMap<>();
        if (!exists()) {
            return teams;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                String name = file.getFileName().toString();
                UUID fileId;
                try {
                    fileId = UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
                } catch (IllegalArgumentException e) {
                    HibiscusTeams.LOGGER.warn("Ignoring {}: team files are named <team id>.snbt", file);
                    continue;
                }
                try {
                    TeamData team = read(file).orElseThrow(() -> new IOException("file vanished"));
                    if (!team.id().equals(fileId)) {
                        throw new IOException("its id " + team.id() + " doesn't match the file name");
                    }
                    teams.put(team.id(), team);
                } catch (IOException | RuntimeException e) {
                    broken.add(fileId);
                    HibiscusTeams.LOGGER.error("Couldn't load team file {}; it will be left untouched until it's fixed: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            HibiscusTeams.LOGGER.error("Couldn't list {}", directory, e);
        }
        return teams;
    }

    // Reads one team file. Throws on unreadable SNBT or data that doesn't make sense.
    static Optional<TeamData> read(Path file) throws IOException {
        Optional<CompoundTag> tag = SnbtFiles.read(file);
        if (tag.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TeamData.load(tag.get()));
        } catch (RuntimeException e) {
            throw new IOException("Invalid team in " + file + ": " + e.getMessage(), e);
        }
    }

    // Creates the directory, so the world counts as having Hibiscus Teams data from now on.
    void createDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    // Writes a team, unless its file is broken; returns whether it was written.
    public boolean write(TeamData team) throws IOException {
        if (broken.contains(team.id())) {
            return false;
        }
        SnbtFiles.write(file(team.id()), team.save());
        return true;
    }

    // Removes a deleted team's file and its backup (never a broken one).
    public void delete(UUID teamId) throws IOException {
        if (broken.contains(teamId)) {
            return;
        }
        Path file = file(teamId);
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".bak"));
    }
}
