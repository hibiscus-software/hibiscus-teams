/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.client;

import net.neoforged.neoforge.common.ModConfigSpec;

// Each player's own choices (config/hibiscusteams-client.toml): how team news reaches them. Client only.
public final class TeamsClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue TOASTS;
    public static final ModConfigSpec.BooleanValue CHAT;

    static {
        BUILDER.push("messages");
        TOASTS = BUILDER
                .comment("Show a toast when you're invited to a party, join one, are removed from one, get an ally request or",
                        "ownership changes hands.")
                .define("toasts", true);
        CHAT = BUILDER
                .comment("Show team news in chat as well (with clickable [Accept] buttons). Messages that never come as toasts",
                        "only show in chat, so with this off you'll miss those.")
                .define("chat", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TeamsClientConfig() {}
}
