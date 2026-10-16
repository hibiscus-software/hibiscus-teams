/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.config;

import net.neoforged.neoforge.common.ModConfigSpec;

// The server's settings, config/hibiscusteams-server.toml (a world's syncedconfig/hibiscusteams-server.toml overrides
// it). Registered as ModConfig.Type.SYNCED, 26.3's server config, so clients get a copy when they connect.
public final class TeamsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue PARTIES_ENABLED;
    public static final ModConfigSpec.IntValue PARTIES_MAX_MEMBERS;
    public static final ModConfigSpec.IntValue PARTIES_NAME_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue PARTIES_ALLOW_FREE_TO_JOIN;
    public static final ModConfigSpec.IntValue INVITES_EXPIRE_MINUTES;
    public static final ModConfigSpec.BooleanValue ALLIES_ENABLED;
    public static final ModConfigSpec.BooleanValue ALLIES_COUNT_FOR_FRIENDLY_FIRE;

    static {
        BUILDER.push("parties");
        PARTIES_ENABLED = BUILDER
                .comment("Whether players may create parties. Server teams work either way.")
                .define("enabled", true);
        PARTIES_MAX_MEMBERS = BUILDER
                .comment("The most members a party may have (0 = no limit). A party's own max_members can only lower it.")
                .defineInRange("maxMembers", 0, 0, 1000);
        PARTIES_NAME_MAX_LENGTH = BUILDER
                .comment("The longest a party or server team name may be.")
                .defineInRange("nameMaxLength", 32, 1, 32);
        PARTIES_ALLOW_FREE_TO_JOIN = BUILDER
                .comment("Whether parties may be set free to join (anyone can join without an invite).")
                .define("allowFreeToJoin", true);
        BUILDER.pop();

        BUILDER.push("invites");
        INVITES_EXPIRE_MINUTES = BUILDER
                .comment("Minutes before a party invite or ally request expires (0 = never).")
                .defineInRange("expireMinutes", 60, 0, 60 * 24 * 30);
        BUILDER.pop();

        BUILDER.push("allies");
        ALLIES_ENABLED = BUILDER
                .comment("Whether parties may become allies.")
                .define("enabled", true);
        ALLIES_COUNT_FOR_FRIENDLY_FIRE = BUILDER
                .comment("Whether a party's friendly_fire setting also covers its allies' members.")
                .define("countForFriendlyFire", false);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TeamsConfig() {}

    // How long an invite made now lasts, in milliseconds (Long.MAX_VALUE when invites never expire).
    public static long inviteLifetimeMillis() {
        int minutes = INVITES_EXPIRE_MINUTES.getAsInt();
        return minutes == 0 ? Long.MAX_VALUE : minutes * 60_000L;
    }
}
