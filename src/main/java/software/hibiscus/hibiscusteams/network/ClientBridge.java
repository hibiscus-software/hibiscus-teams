/*
 * Copyright (C), 2026 Hibiscus Software. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package software.hibiscus.hibiscusteams.network;

import software.hibiscus.hibiscusteams.client.ClientTeamState;

// The step from a payload handler to client code. The handlers are registered on both sides but only ever run on a
// client, and ClientTeamState is only loaded when one of these is called, so a dedicated server never loads it.
final class ClientBridge {
    private ClientBridge() {}

    static void summaries(TeamPayloads.Summaries payload) {
        ClientTeamState.applySummaries(payload);
    }

    static void view(TeamPayloads.View payload) {
        ClientTeamState.applyView(payload.view());
    }

    static void notify(TeamPayloads.Notify payload) {
        ClientTeamState.notify(payload.kind(), payload.message(), payload.toast());
    }

    static void result(TeamPayloads.Result payload) {
        ClientTeamState.result(payload.ok(), payload.message());
    }
}
