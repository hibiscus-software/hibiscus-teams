/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import software.hibiscus.hibiscuslib.api.team.TeamsApi;
import software.hibiscus.hibiscusteams.command.TeamPermissions;
import software.hibiscus.hibiscusteams.command.TeamsCommand;
import software.hibiscus.hibiscusteams.config.TeamsConfig;
import software.hibiscus.hibiscusteams.gametest.HibiscusTeamsGameTests;
import software.hibiscus.hibiscusteams.integration.ChatIntegration;
import software.hibiscus.hibiscusteams.integration.ScoreboardMirror;
import software.hibiscus.hibiscusteams.network.TeamNetwork;
import software.hibiscus.hibiscusteams.network.TeamSync;
import software.hibiscus.hibiscusteams.team.FriendlyFire;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamOps;

// Hibiscus Teams' common entrypoint (both sides). Must match the modId in META-INF/neoforge.mods.toml. Installs the
// Teams API implementation (replacing Hibiscus Lib's solo-team fallback; Lib constructs first, see ordering="AFTER")
// and hooks the server lifecycle, logins, commands, friendly fire, chat, the scoreboard mirror, and the network and
// client sync.
@Mod(HibiscusTeams.MODID)
public class HibiscusTeams {
    public static final String MODID = "hibiscusteams";
    public static final Logger LOGGER = LogUtils.getLogger();

    public HibiscusTeams(IEventBus modEventBus, ModContainer modContainer) {
        TeamsApi.install(TeamManagerImpl.INSTANCE, MODID);
        // SYNCED is 26.3's server config: loaded as the server starts (a world's syncedconfig/ copy wins) and sent to clients.
        modContainer.registerConfig(ModConfig.Type.SYNCED, TeamsConfig.SPEC, MODID + "-server.toml");
        TeamPermissions.init();
        HibiscusTeamsGameTests.register(modEventBus);
        TeamNetwork.register(modEventBus);
        TeamSync.register();
        ScoreboardMirror.register();
        ChatIntegration.register();

        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> TeamManagerImpl.INSTANCE.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            TeamManagerImpl.INSTANCE.stop();
            TeamSync.onServerStop();
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            TeamManagerImpl.INSTANCE.tick();
            TeamSync.tick(event.getServer());
            ScoreboardMirror.tick(event.getServer());
            ChatIntegration.tick(event.getServer());
        });
        NeoForge.EVENT_BUS.addListener(HibiscusTeams::onLogin);
        NeoForge.EVENT_BUS.addListener(HibiscusTeams::onLogout);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> TeamsCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(FriendlyFire::onIncomingDamage);

        if (ModList.get().isLoaded("ftbteams")) {
            LOGGER.warn("FTB Teams is installed too. Hibiscus Teams doesn't bridge to it: the two keep separate teams, "
                    + "and mods using the Hibiscus Lib Teams API see only Hibiscus Teams'.");
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamManagerImpl.INSTANCE.onLogin(player);
            TeamSync.onLogin(player);
            ScoreboardMirror.markDirty();
            ChatIntegration.markDirty();
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TeamOps.forget(event.getEntity().getUUID());
        TeamNetwork.forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamSync.onLogout(player);
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
