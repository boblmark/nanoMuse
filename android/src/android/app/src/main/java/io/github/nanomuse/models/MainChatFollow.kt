package io.github.nanomuse.models

import io.github.nanomuse.home.MainChat

/**
 * The main chat follows the chat slot. Every chat keeps the binding it was made with, and the
 * chat slot (Settings › Models, the "Use it for" card, a pick in a chat's own picker) is the
 * default for new ones; but the main chat is the one conversation the home always shows and is
 * never new, so on 0.1.41 the Models page could not move it off the provider it started on: a
 * person who saved a key of their own saw their side chats answer through it while the main chat
 * kept nanoMuse Cloud under the face. Now the main chat's binding moves with the slot, as the
 * iPhone does (`NanoMuseModelSlots.mainChatFollows`); side chats are left as they are.
 *
 * The rule alone, so it can be tested without a database: which session follows, what is
 * written, and when nothing is (the binding already says so, which also keeps the
 * picker → `followPick` → main chat round from going on). The writes are in
 * [ModelSlots.mainChatFollows].
 */
object MainChatFollow {
    /** What the chat slot was last set to: the group that leads with the pick, and the pick. */
    data class Pick(val groupId: String, val entryId: String)

    /** The session that follows: the persisted main chat, never a draft (it has no row yet). */
    fun target(mainSessionId: String?): String? = mainSessionId?.takeIf { !MainChat.isDraftId(it) }

    /**
     * The binding the main chat gets: the group, leading with the pick, in the shape the chat
     * view-model writes for a group pick (`restoreFromBinding` reads it back).
     */
    fun binding(pick: Pick): String =
        """{"type":"group","groupId":"${pick.groupId}","lastEntryId":"${pick.entryId}"}"""

    /** Nothing to write when the session's binding already says what the slot says. */
    fun alreadySays(current: String?, pick: Pick): Boolean = current == binding(pick)

    /**
     * Whether a chat that is open now needs to move: it is the main chat and shows another
     * entry, or the same entry through another group.
     */
    fun shouldMove(isMain: Boolean, activeEntryId: String?, selectedGroupId: String?, pick: Pick): Boolean =
        isMain && !(activeEntryId == pick.entryId && selectedGroupId == pick.groupId)
}
