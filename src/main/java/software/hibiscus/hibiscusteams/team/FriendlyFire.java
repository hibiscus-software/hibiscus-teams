/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import software.hibiscus.hibiscusteams.config.TeamsConfig;

// Stops players hurting their teammates (and, if the server says so, their allies) when the team has friendly_fire
// off. Arrows and other projectiles count as their shooter's attack.
public final class FriendlyFire {
    private FriendlyFire() {}

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return;
        }
        if (!attacker.equals(victim) && isBlocked(attacker.getUUID(), victim.getUUID())) {
            event.setCanceled(true);
        }
    }

    // Whether the attacker may not hurt the victim: same party or server team with friendly_fire off, or allied
    // teams (when allies.countForFriendlyFire is on) unless both allow it.
    public static boolean isBlocked(UUID attacker, UUID victim) {
        TeamManagerImpl teams = TeamManagerImpl.INSTANCE;
        if (!teams.isRunning()) {
            return false;
        }
        TeamData attackerTeam = teams.teamOf(attacker);
        if (attackerTeam.type().isPlayer()) {
            return false;
        }
        TeamData victimTeam = teams.teamOf(victim);
        if (attackerTeam.equals(victimTeam)) {
            return !attackerTeam.friendlyFire();
        }
        if (TeamsConfig.ALLIES_COUNT_FOR_FRIENDLY_FIRE.getAsBoolean() && attackerTeam.allies().contains(victimTeam.id())) {
            return !(attackerTeam.friendlyFire() && victimTeam.friendlyFire());
        }
        return false;
    }
}
