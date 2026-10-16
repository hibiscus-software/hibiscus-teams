/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams;

import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import software.hibiscus.hibiscuslib.api.team.ClientTeams;
import software.hibiscus.hibiscuslib.button.ButtonBadge;
import software.hibiscus.hibiscuslib.button.ButtonSpec;
import software.hibiscus.hibiscuslib.button.HibiscusButtons;
import software.hibiscus.hibiscusteams.client.ClientTeamState;
import software.hibiscus.hibiscusteams.client.TeamsClientConfig;
import software.hibiscus.hibiscusteams.client.screen.TeamsScreen;

// Client-only entrypoint; never loaded on dedicated servers. Installs the client side of the Teams API, the client
// config, and the inventory button (with its key mapping, which has to be registered during mod construction).
@Mod(value = HibiscusTeams.MODID, dist = Dist.CLIENT)
public class HibiscusTeamsClient {
    public HibiscusTeamsClient(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, TeamsClientConfig.SPEC, HibiscusTeams.MODID + "-client.toml");
        // Mods screen > Hibiscus Teams > Config
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        ClientTeams.install(ClientTeamState.PROVIDER);

        HibiscusButtons.register(HibiscusTeams.id("teams"), ButtonSpec.builder()
                .sprite(HibiscusTeams.id("button/teams"))
                .tooltip(Component.translatable("hibiscusteams.button.tooltip"))
                .onClick(TeamsScreen::open)
                // Only on servers running Hibiscus Teams (they send the player's view on login).
                .visibleWhen(ClientTeamState::isConnected)
                .badge(() -> ButtonBadge.count(ClientTeamState.badgeCount()))
                .withKeyMapping()
                .build());

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientTeamState.clear());
    }
}
