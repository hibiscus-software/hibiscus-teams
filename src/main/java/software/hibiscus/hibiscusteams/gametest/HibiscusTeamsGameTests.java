/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import software.hibiscus.hibiscusteams.HibiscusTeams;

// Registers every Hibiscus Teams gametest, the same way Hibiscus Lib registers its own. All run in one batch on an
// empty structure; ./gradlew runGameTestServer runs them (and Lib's) and exits, failing the build if any fails.
public final class HibiscusTeamsGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, HibiscusTeams.MODID);

    // Every test, by name.
    private static final Map<String, Consumer<GameTestHelper>> TESTS = new LinkedHashMap<>();

    static {
        TESTS.put("api_installed", TeamsCoreGameTests::apiInstalled);
        TESTS.put("player_team_shape", TeamsCoreGameTests::playerTeamShape);
        TESTS.put("invite_accept_member", TeamsCoreGameTests::inviteAcceptMember);
        TESTS.put("leave_returns_player_team", TeamsCoreGameTests::leaveReturnsPlayerTeam);
        TESTS.put("owner_cannot_leave", TeamsCoreGameTests::ownerCannotLeave);
        TESTS.put("kick", TeamsCoreGameTests::kick);
        TESTS.put("rank_limits", TeamsCoreGameTests::rankLimits);
        TESTS.put("rename_unique", TeamsCoreGameTests::renameUnique);
        TESTS.put("free_to_join", TeamsCoreGameTests::freeToJoin);
        TESTS.put("invite_expiry", TeamsCoreGameTests::inviteExpiry);
        TESTS.put("allies", TeamsCoreGameTests::allies);
        TESTS.put("friendly_fire", TeamsCoreGameTests::friendlyFire);
        TESTS.put("extra_data_persists", TeamsCoreGameTests::extraDataPersists);
        TESTS.put("events_in_order", TeamsCoreGameTests::eventsInOrder);
        TESTS.put("join_request_denied", TeamsCoreGameTests::joinRequestDenied);
        TESTS.put("server_teams", TeamsCoreGameTests::serverTeams);
        TESTS.put("admin_force_leave_owner", TeamsCoreGameTests::adminForceLeaveOwner);
        TESTS.put("fallback_migrated", TeamsCoreGameTests::fallbackMigrated);
        TESTS.put("broken_file_untouched", TeamsCoreGameTests::brokenFileUntouched);
        TESTS.put("command_flow", CommandGameTests::commandFlow);
        TESTS.put("sync_view_follows_rank", SyncGameTests::viewFollowsRank);
        TESTS.put("sync_payloads_round_trip", SyncGameTests::payloadsRoundTrip);
        TESTS.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
    }

    private HibiscusTeamsGameTests() {}

    public static void register(IEventBus modEventBus) {
        FUNCTIONS.register(modEventBus);
        modEventBus.addListener(HibiscusTeamsGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(id("default"));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, Identifier.withDefaultNamespace("empty"),
                100, 0, true, Rotation.NONE, false, 1, 1, false, 8);
        TESTS.keySet().forEach(name -> event.registerTest(id(name), new FunctionGameTestInstance(function(name), data)));
    }

    static Identifier id(String path) {
        return HibiscusTeams.id(path);
    }

    private static ResourceKey<Consumer<GameTestHelper>> function(String path) {
        return ResourceKey.create(Registries.TEST_FUNCTION, id(path));
    }
}
