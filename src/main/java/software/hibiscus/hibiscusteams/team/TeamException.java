/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import net.minecraft.network.chat.Component;

// Why a team operation was refused, as a message for the player. Thrown by TeamOps before anything changes; commands
// show it as a failure and the GUI as an error.
public final class TeamException extends RuntimeException {
    private final Component reason;

    public TeamException(Component reason) {
        super(reason.getString(), null, false, false);
        this.reason = reason;
    }

    // hibiscusteams.error.<key>, with arguments.
    public static TeamException of(String key, Object... args) {
        return new TeamException(Component.translatable("hibiscusteams.error." + key, args));
    }

    public Component reason() {
        return reason;
    }
}
