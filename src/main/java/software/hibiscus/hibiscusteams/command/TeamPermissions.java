/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.command;

import net.minecraft.server.permissions.PermissionLevel;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import software.hibiscus.hibiscuslib.util.PermissionLevels;
import software.hibiscus.hibiscusteams.HibiscusTeams;

// The NeoForge permission nodes for admin commands (hibiscusteams.admin.*), granted by default to ops (level 2) so a
// permissions mod can hand them out more finely. Player commands need no node: ranks decide what a player may do.
// Loaded from the mod constructor so the nodes are declared before the server starts.
public final class TeamPermissions {
    // server create/delete/add/remove/settings, and editing server teams from the GUI.
    public static final PermissionNode<Boolean> SERVER_TEAMS = node("admin.server");
    // admin force_join / force_leave.
    public static final PermissionNode<Boolean> MEMBERS = node("admin.members");
    // admin rename.
    public static final PermissionNode<Boolean> RENAME = node("admin.rename");
    // admin disband.
    public static final PermissionNode<Boolean> DISBAND = node("admin.disband");
    // admin reload.
    public static final PermissionNode<Boolean> RELOAD = node("admin.reload");
    // admin export.
    public static final PermissionNode<Boolean> EXPORT = node("admin.export");

    private TeamPermissions() {}

    private static PermissionNode<Boolean> node(String name) {
        return PermissionLevels.node(HibiscusTeams.MODID, name, PermissionLevel.GAMEMASTERS);
    }

    // Forces the class to load (and so the nodes to be declared).
    public static void init() {}
}
