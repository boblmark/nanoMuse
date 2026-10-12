package io.github.nanomuse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main chat follows the chat slot; side chats and drafts do not (MainChatFollow). */
class MainChatFollowTest {
    private val pick = MainChatFollow.Pick(groupId = "g-own", entryId = "e-qwen")

    @Test
    fun `the persisted main chat follows, a draft or none does not`() {
        assertEquals("s-main", MainChatFollow.target("s-main"))
        assertNull(MainChatFollow.target("__new__draft"))
        assertNull(MainChatFollow.target(null))
    }

    @Test
    fun `the binding is the group pick the chat view-model would write`() {
        assertEquals("""{"type":"group","groupId":"g-own","lastEntryId":"e-qwen"}""", MainChatFollow.binding(pick))
    }

    @Test
    fun `nothing is written when the row already says so, so the round stops`() {
        assertTrue(MainChatFollow.alreadySays("""{"type":"group","groupId":"g-own","lastEntryId":"e-qwen"}""", pick))
        assertFalse(MainChatFollow.alreadySays("""{"type":"entry","entryId":"e-qwen"}""", pick))
        assertFalse(MainChatFollow.alreadySays("""{"type":"group","groupId":"g-cloud","lastEntryId":"e-ds"}""", pick))
        assertFalse(MainChatFollow.alreadySays(null, pick))
    }

    @Test
    fun `an open main chat moves unless it shows the pick through that group, a side chat never`() {
        // pinned to another model, or on another group: moves
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-ds", selectedGroupId = null, pick = pick))
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = "g-cloud", pick = pick))
        // the same entry pinned directly (the chat's own picker just chose it): the group binding takes over
        assertTrue(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = null, pick = pick))
        // already there
        assertFalse(MainChatFollow.shouldMove(isMain = true, activeEntryId = "e-qwen", selectedGroupId = "g-own", pick = pick))
        // a side chat keeps its model whatever it shows
        assertFalse(MainChatFollow.shouldMove(isMain = false, activeEntryId = "e-ds", selectedGroupId = null, pick = pick))
    }
}
