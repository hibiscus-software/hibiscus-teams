/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.screenshots;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.modlist.ModListScreen;
import software.hibiscus.hibiscusteams.HibiscusTeams;
import software.hibiscus.hibiscusteams.client.ClientTeamState;
import software.hibiscus.hibiscusteams.client.screen.CreatePartyPopup;
import software.hibiscus.hibiscusteams.client.screen.PlayerPickerPopup;
import software.hibiscus.hibiscusteams.client.screen.TeamPickerPopup;
import software.hibiscus.hibiscusteams.client.screen.TeamsScreen;
import software.hibiscus.hibiscusteams.team.Actor;
import software.hibiscus.hibiscusteams.team.TeamData;
import software.hibiscus.hibiscusteams.team.TeamManagerImpl;
import software.hibiscus.hibiscusteams.team.TeamMessages;
import software.hibiscus.hibiscusteams.team.TeamOps;
import software.hibiscus.hibiscusteams.team.TeamSetting;

// Development only (src/screenshots, never in the mod jar): ./gradlew runScreenshotClient opens a flat creative world,
// sets up demo parties on the integrated server, puts every Teams screen state on screen at each GUI scale and window
// size the README promises, saves a screenshot of each to run/screenshotClient/screenshots/, and quits. The same idea
// as Hibiscus Lib's harness; its pictures are what the layout is checked against and what the README shows.
@EventBusSubscriber(modid = HibiscusTeams.MODID, value = Dist.CLIENT)
public final class ScreenshotHarness {
    private static final boolean ENABLED = Boolean.getBoolean("hibiscusteams.screenshots");
    private static final String WORLD = "hibiscusteams_screenshots";
    // Demo players: the client has never seen them, so they get default skins; the name cache gives their names.
    private static final List<String> NAMES = List.of("Ada", "Basil", "Coral", "Dahlia", "Elm", "Fern", "Ginger", "Hazel");

    private record Step(Runnable action, int waitTicks, @Nullable String shot) {}

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static boolean worldRequested;
    private static boolean planned;
    private static int wait;
    private static int inWorldTicks;

    private ScreenshotHarness() {}

    private static UUID demo(String name) {
        return UUID.nameUUIDFromBytes(("hibiscusteams-demo-" + name).getBytes(StandardCharsets.UTF_8));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            if (!worldRequested && minecraft.gui.screen() instanceof TitleScreen) {
                worldRequested = true;
                openWorld(minecraft);
            }
            return;
        }
        if (++inWorldTicks < 100) {
            return;
        }
        if (!planned) {
            planned = true;
            minecraft.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            plan(minecraft);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step step = STEPS.poll();
        if (step == null) {
            HibiscusTeams.LOGGER.info("Screenshot harness finished");
            minecraft.stop();
            return;
        }
        if (step.shot() != null) {
            File dir = minecraft.gameDirectory;
            String name = step.shot() + ".png";
            Screenshot.grab(dir, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                    message -> HibiscusTeams.LOGGER.info("Screenshot {}: {}", name, message.getString()));
        }
        step.action().run();
        wait = step.waitTicks();
    }

    private static void openWorld(Minecraft minecraft) {
        if (minecraft.getLevelSource().levelExists(WORLD)) {
            minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
            return;
        }
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(26_3L, false, false),
                WorldPresets::createTestWorldDimensions, new TitleScreen());
    }

    private static void then(Runnable action, int waitTicks) {
        STEPS.add(new Step(action, waitTicks, null));
    }

    // Takes a screenshot. Toasts from setting up the demo teams are cleared first; the toast shot adds its own after.
    private static void shot(String name) {
        STEPS.add(new Step(() -> {}, 2, name));
    }

    private static void clearToasts(Minecraft minecraft) {
        then(() -> minecraft.gui.toastManager().clear(), 2);
    }

    // Runs something on the integrated server's thread (team changes happen there), then waits for the sync.
    private static void onServer(Minecraft minecraft, Consumer<MinecraftServer> action) {
        then(() -> {
            IntegratedServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> action.accept(server));
            }
        }, 20);
    }

    private static @Nullable TeamsScreen teams(Minecraft minecraft) {
        return minecraft.gui.screen() instanceof TeamsScreen screen ? screen : null;
    }

    // ---- Demo data (server thread) ----

    // Back to the start: no groups, the local player on their own, demo players in the name cache.
    private static void reset(MinecraftServer server) {
        for (TeamData team : TeamManagerImpl.INSTANCE.groups()) {
            TeamOps.disband(Actor.admin(null), team);
        }
        for (String name : NAMES) {
            server.services().nameToIdCache().add(new NameAndId(demo(name), name));
        }
    }

    // Two other parties: one inviting the player, one open to all.
    private static void soloScene(MinecraftServer server, UUID self) {
        reset(server);
        TeamData coral = TeamOps.createParty(demo("Coral"), "Coral Reef");
        TeamOps.set(Actor.admin(null), coral, TeamSetting.COLOR, "#F07AC0");
        TeamOps.set(Actor.admin(null), coral, TeamSetting.ICON, "minecraft:brain_coral");
        TeamOps.forceJoin(demo("Dahlia"), coral);
        TeamOps.invite(Actor.player(demo("Coral")), self);
        TeamData tide = TeamOps.createParty(demo("Elm"), "Tidepool");
        TeamOps.set(Actor.admin(null), tide, TeamSetting.COLOR, "#3AB7E8");
        TeamOps.set(Actor.admin(null), tide, TeamSetting.FREE_TO_JOIN, "true");
        TeamOps.set(Actor.admin(null), tide, TeamSetting.ICON, "minecraft:nautilus_shell");
        TeamData raiders = TeamOps.createParty(demo("Hazel"), "Night Raiders");
        TeamOps.set(Actor.admin(null), raiders, TeamSetting.COLOR, "#6C5CE7");
        TeamOps.set(Actor.admin(null), raiders, TeamSetting.FREE_TO_JOIN, "true");
        TeamOps.createServerTeam(null, "Spawn Crew");
    }

    // The player owns a party with officers, members, an invite out, an ally, an ally request and an enemy.
    private static void partyScene(MinecraftServer server, UUID self) {
        soloScene(server, self);
        TeamData coral = TeamManagerImpl.INSTANCE.byName("Coral Reef").orElseThrow();
        TeamData tide = TeamManagerImpl.INSTANCE.byName("Tidepool").orElseThrow();
        TeamData raiders = TeamManagerImpl.INSTANCE.byName("Night Raiders").orElseThrow();
        TeamOps.decline(self, coral);
        TeamData party = TeamOps.createParty(self, "Hibiscus Crew");
        Actor me = Actor.player(self);
        TeamOps.set(me, party, TeamSetting.COLOR, "#F2557A");
        TeamOps.set(me, party, TeamSetting.TAG, "HIBI");
        TeamOps.set(me, party, TeamSetting.ICON, "minecraft:pink_tulip");
        TeamOps.set(me, party, TeamSetting.DESCRIPTION, "Builders of the reef base. Meet at spawn on Fridays.");
        for (String name : List.of("Ada", "Basil", "Fern", "Ginger")) {
            TeamOps.forceJoin(demo(name), party);
        }
        TeamOps.promote(me, demo("Ada"));
        TeamOps.invite(me, demo("Hazel"));
        TeamOps.requestAlly(Actor.player(demo("Elm")), party);
        TeamOps.acceptAlly(me, tide);
        TeamOps.requestAlly(Actor.player(demo("Coral")), party);
        TeamOps.addEnemy(me, raiders);
    }

    // The player is a plain member of someone else's party, so its settings are read-only.
    private static void memberScene(MinecraftServer server, UUID self) {
        soloScene(server, self);
        TeamOps.accept(self, TeamManagerImpl.INSTANCE.byName("Coral Reef").orElseThrow());
        TeamOps.set(Actor.admin(null), TeamManagerImpl.INSTANCE.byName("Coral Reef").orElseThrow(), TeamSetting.DESCRIPTION,
                "Reef keepers. Ask Coral before building near the lagoon.");
    }

    // ---- The plan ----

    private static void plan(Minecraft minecraft) {
        UUID self = minecraft.player.getUUID();
        int[][] sizes = {{1280, 720, 1}, {1280, 720, 2}, {1280, 720, 3}, {1440, 960, 4}};
        for (int[] size : sizes) {
            String tag = size[0] + "x" + size[1] + "_scale" + size[2];
            then(() -> {
                minecraft.getWindow().setWindowed(size[0], size[1]);
                minecraft.options.guiScale().set(size[2]);
                minecraft.resizeGui();
            }, 20);
            then(minecraft::resizeGui, 5);

            // On your own, with an invite and open parties; the button's badge counts the invite.
            onServer(minecraft, server -> soloScene(server, self));
            then(() -> minecraft.player.connection.sendCommand("gamemode survival"), 10);
            clearToasts(minecraft);
            then(() -> minecraft.gui.setScreen(new InventoryScreen(minecraft.player)), 10);
            shot("inventory_" + tag);
            then(() -> {
                minecraft.gui.setScreen(null);
                minecraft.player.connection.sendCommand("gamemode creative");
            }, 10);
            then(() -> minecraft.gui.setScreen(new TeamsScreen(null)), 10);
            shot("solo_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.openPopup(new CreatePartyPopup());
                }
            }, 10);
            shot("create_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.topPopup().ifPresent(popup -> popup.cancel());
                }
                // A stack of three, as several things at once would make: they should sit evenly, one slot each.
                Component invite = Component.literal("Coral invited you to join ")
                        .append(Component.literal("Coral Reef").withColor(0xF07AC0)).append(".");
                ClientTeamState.notify(TeamMessages.Kind.INVITED, invite, invite);
                Component joined = Component.literal("Ada joined ").append(Component.literal("Hibiscus Crew").withColor(0xF2557A)).append(".");
                ClientTeamState.notify(TeamMessages.Kind.JOINED, joined, joined);
                Component ally = Component.empty().append(Component.literal("Tidepool").withColor(0x3AB7E8))
                        .append(" wants to be allies with your party, for the reef raid on Friday.");
                ClientTeamState.notify(TeamMessages.Kind.ALLY_REQUEST, ally, ally);
            }, 30);
            shot("toast_" + tag);
            then(() -> minecraft.gui.toastManager().clear(), 2);

            // Owner of a full party.
            onServer(minecraft, server -> partyScene(server, self));
            clearToasts(minecraft);

            // Chat: a public message with the party tag before the name, then a team chat message.
            then(() -> {
                minecraft.gui.setScreen(null);
                minecraft.gui.hud.getChat().clearMessages(false);
                minecraft.player.connection.sendChat("Anyone up for the reef raid tonight?");
            }, 10);
            then(() -> minecraft.player.connection.sendCommand("teams msg Meet at the boathouse at 8."), 20);
            shot("chat_" + tag);
            then(() -> minecraft.gui.setScreen(new TeamsScreen(null)), 10);
            shot("members_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.showMemberMenu(demo("Basil"));
                }
            }, 5);
            shot("member_menu_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.topPopup().ifPresent(popup -> popup.cancel());
                    ClientTeamState.view().ifPresent(view -> screen.openPopup(new PlayerPickerPopup(view.mine())));
                }
            }, 5);
            shot("invite_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.topPopup().ifPresent(popup -> popup.cancel());
                    screen.showTab(1);
                }
            }, 5);
            shot("allies_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    ClientTeamState.view().ifPresent(view -> screen.openPopup(new TeamPickerPopup(view.mine())));
                }
            }, 5);
            shot("ally_picker_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.topPopup().ifPresent(popup -> popup.cancel());
                    screen.showTab(2);
                }
            }, 5);
            shot("settings_" + tag);
            then(() -> scrollToBottom(minecraft), 5);
            shot("settings_bottom_" + tag);
            then(() -> {
                TeamsScreen screen = teams(minecraft);
                if (screen != null) {
                    screen.showDisband();
                }
            }, 5);
            shot("disband_" + tag);

            // A plain member: read-only settings.
            onServer(minecraft, server -> memberScene(server, self));
            clearToasts(minecraft);
            then(() -> {
                TeamsScreen screen = new TeamsScreen(null);
                minecraft.gui.setScreen(screen);
                screen.showTab(2);
            }, 10);
            shot("settings_readonly_" + tag);

            // The mod list, with Hibiscus Teams selected: its icon and banner.
            then(() -> minecraft.gui.setScreen(ModListScreen.create(null)), 10);
            then(() -> selectMod(minecraft, "Hibiscus Teams"), 10);
            shot("mod_list_" + tag);
            then(() -> minecraft.gui.setScreen(null), 5);
        }
        onServer(minecraft, ScreenshotHarness::reset);
    }

    private static void scrollToBottom(Minecraft minecraft) {
        Screen screen = minecraft.gui.screen();
        if (screen != null) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof software.hibiscus.hibiscuslib.client.gui.widget.ScrollPanel panel) {
                    panel.setScrollAmount(panel.maxScrollAmount());
                }
            }
        }
    }

    // Types the name into the mod list's search box and selects the one entry left.
    @SuppressWarnings("rawtypes")
    private static void selectMod(Minecraft minecraft, String name) {
        Screen screen = minecraft.gui.screen();
        if (screen == null) {
            return;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox search) {
                search.setValue(name);
            }
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractSelectionList list && !list.children().isEmpty()) {
                // Click the first row, as a player would (its entry type isn't reachable from here).
                double x = list.getX() + 30;
                double y = list.getY() + 10;
                list.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
            }
        }
    }
}
