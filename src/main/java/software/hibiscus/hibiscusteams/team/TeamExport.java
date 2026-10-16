/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;

// /teams admin export: every team, as saved, in one JSON file under logs/ for admins to inspect or feed to tools.
public final class TeamExport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private TeamExport() {}

    // Writes the export and returns its path.
    public static Path export(MinecraftServer server) throws IOException {
        JsonArray teams = new JsonArray();
        for (TeamData team : TeamManagerImpl.INSTANCE.allTeams()) {
            JsonElement json = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, team.save());
            teams.add(json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("exported", LocalDateTime.now().toString());
        root.add("teams", teams);
        Path file = server.getServerDirectory().resolve("logs").resolve("hibiscusteams-export-" + LocalDateTime.now().format(STAMP) + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        return file;
    }
}
