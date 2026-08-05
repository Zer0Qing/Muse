package io.zer0.muse.data.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationTreeSnapshotStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun user(content: String, group: String, index: Int, count: Int, at: Long) =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = content,
            createdAt = at,
            variantGroupId = group,
            variantIndex = index,
            variantCount = count,
        )

    private fun assistant(content: String, group: String, parent: String, index: Int, count: Int, at: Long) =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = content,
            createdAt = at,
            variantGroupId = group,
            variantIndex = index,
            variantCount = count,
            parentGroupId = parent,
        )

    @Test
    fun snapshot_roundTripsSelection() = runBlocking {
        val store = ConversationTreeSnapshotStore(context)
        val u1 = user("提问A", "ug1", 0, 2, 100)
        val a1a = assistant("回答1", "ag1", u1.id.toString(), 0, 2, 101)
        val a1b = assistant("回答2", "ag1", u1.id.toString(), 1, 2, 103)
        val u2 = user("提问A改", "ug1", 1, 2, 102)
        val messages = listOf(u1, a1a, a1b, u2)

        val initial = ConversationTree.build(messages)
        val selected = initial
            .selectUserVariant(initial.userNodes.first().userId, 0)
            .selectAssistantVariant(u1.id.toString(), "ag1", 0)
        store.save("session-test", selected)

        val loaded = store.load("session-test")
        assertNotNull(loaded)
        val rebuilt = ConversationTree.build(messages, loaded)

        assertEquals("提问A", rebuilt.selectedUserVariant?.content)
        assertEquals("回答1", rebuilt.displayMessages.last().content)
    }
}
