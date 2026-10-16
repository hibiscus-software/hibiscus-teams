/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

// Who is asking TeamOps for a change: a player (checked against their rank) or an admin (op commands, the console),
// who may change any team. The caller decides admin from a permission check; TeamOps trusts it.
public record Actor(@Nullable UUID player, boolean admin) {
    public static Actor player(UUID player) {
        return new Actor(player, false);
    }

    public static Actor admin(@Nullable UUID player) {
        return new Actor(player, true);
    }

    public Optional<UUID> playerId() {
        return Optional.ofNullable(player);
    }

    // The player behind a player action; player actions always have one.
    public UUID requirePlayer() {
        if (player == null) {
            throw TeamException.of("player_only");
        }
        return player;
    }
}
