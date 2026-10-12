package io.github.nanomuse.hub

import java.util.concurrent.ConcurrentHashMap

/**
 * The tasks other devices have running on this phone, so `stop {call}` or
 * `stop {conversation}` (docs/hub.md) can find the run to end. Pure bookkeeping: [HubActions] registers a task when its prompt starts and
 * clears it when the answer goes out; the unit tests exercise the lookup rules here.
 */
internal class HubTasks {
    private class Run(val senderId: String, val conversation: String, val sessionId: String) {
        @Volatile var stopped = false
    }

    private val runs = ConcurrentHashMap<String, Run>()

    /** The conversation a task from [senderId] lands in: the one it names, else one per device. */
    fun conversationKey(senderId: String, conversation: String?): String =
        conversation?.trim().orEmpty().ifBlank { "from-" + senderId.ifBlank { "unknown" } }

    fun started(callId: String, senderId: String, conversation: String, sessionId: String) {
        runs[callId] = Run(senderId, conversation, sessionId)
    }

    /** Forgets the run; true when a `stop` ended it, so the task answers `cancelled`. */
    fun finished(callId: String): Boolean = runs.remove(callId)?.stopped == true

    /**
     * The session a `stop` from [senderId] would end: the run opened for [callId], else the
     * sender's run in [conversation] (its own per-device conversation when none is named). Only
     * the device that asked for a task may stop it; null when there is nothing of theirs to stop.
     */
    fun find(senderId: String, callId: String?, conversation: String?): String? {
        val run = callId?.takeIf { it.isNotBlank() }?.let { runs[it] }
            ?: runs.values.firstOrNull { it.conversation == conversationKey(senderId, conversation) }
        return run?.takeIf { it.senderId == senderId }?.sessionId
    }

    /** The run in [sessionId] was ended by a `stop`; its task answers `cancelled` when it returns. */
    fun markStopped(sessionId: String) {
        runs.values.filter { it.sessionId == sessionId }.forEach { it.stopped = true }
    }
}
