/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(HibiscusTeams.MODID)
public class HibiscusTeams {
    public static final String MODID = "hibiscusteams";
    public static final Logger LOGGER = LogUtils.getLogger();

    public HibiscusTeams(IEventBus modEventBus, ModContainer modContainer) {
    }
}
