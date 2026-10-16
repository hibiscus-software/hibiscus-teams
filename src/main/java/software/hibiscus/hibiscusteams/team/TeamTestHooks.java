/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.team;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

// For the gametests only: reaches into team state that normal code changes through TeamOps, so tests needn't wait
// an hour for an invite to lapse or restart the server to read a save back.
public final class TeamTestHooks {
    private TeamTestHooks() {}

    // Makes a pending invite lapse now.
    public static void expireInvite(TeamData team, UUID player) {
        TeamData.Invite invite = team.invites().get(player);
        if (invite != null) {
            team.putInvite(player, new TeamData.Invite(invite.by(), System.currentTimeMillis() - 1));
        }
    }

    // Runs the periodic clean-up of lapsed invites and ally requests.
    public static void pruneExpired() {
        TeamManagerImpl.INSTANCE.pruneExpired();
    }

    // Saves now and reads the team's file back, as the next server start would.
    public static Optional<TeamData> saveAndReload(TeamData team) throws IOException {
        TeamManagerImpl.INSTANCE.saveNow();
        return TeamStorage.read(TeamManagerImpl.INSTANCE.storage().file(team.id()));
    }
}
