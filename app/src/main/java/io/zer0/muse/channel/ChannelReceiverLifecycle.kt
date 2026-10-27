package io.zer0.muse.channel

import kotlinx.coroutines.CompletableDeferred

/** Waits for a socket termination signal and always releases transport resources. */
internal suspend fun awaitChannelSocketTermination(disconnected: CompletableDeferred<Unit>, cleanup: () -> Unit) {
    try {
        disconnected.await()
    } finally {
        cleanup()
    }
}
