/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.util.Locale;
import java.util.UUID;

// The team colour presets, the same 16 as Hibiscus Lib's ColorPicker (which is a client class, so the server can't
// read them from there). New teams get one picked from their id.
public final class TeamColors {
    public static final int[] PRESETS = {
            0xF2557A, 0xE5484D, 0xF28B30, 0xE8B04B, 0xF2C14E, 0x8BD346, 0x4FC78A, 0x2EC4B6,
            0x3AB7E8, 0x4C7BF2, 0x6C5CE7, 0xA363D9, 0xF07AC0, 0x9C6B4E, 0x9A9DA3, 0xE6E6E6
    };

    private TeamColors() {}

    public static int forId(UUID id) {
        return PRESETS[Math.floorMod(id.hashCode(), PRESETS.length)];
    }

    public static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }
}
