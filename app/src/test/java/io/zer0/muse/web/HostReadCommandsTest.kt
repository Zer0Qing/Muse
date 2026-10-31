package io.zer0.muse.web

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** assistants.list / providers.list / sessions.detail 的载荷投影测试。 */
class HostReadCommandsTest {

    private val workDir = Files.createTempDirectory("host-read-commands").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val avatarDir = File(workDir, "avatar").apply { mkdirs() }

    private fun mediaLinks() = HostMediaLinks(HostMediaStore(imageDir, voiceDir, avatarDir))

    @Test
    fun `assistants list returns an empty array on an empty database`() {
        val event = HostReadCommands.assistantsEvent(
            emptyList(),
            defaultAssistantId = null,
            requestId = "req-1",
            mediaLinks = mediaLinks(),
        )

        assertEquals(HostReadCommands.TYPE_ASSISTANTS, event.type)
        assertEquals("req-1", event.requestId)
        assertTrue("空库应回空数组而不是报错", event.assistants.isEmpty())
        assertNull(event.error)
    }

    @Test
    fun `assistant avatar on disk becomes a media route`() {
        val avatar = File(avatarDir, "muse.png").apply { writeBytes(PNG_BYTES) }
        val store = HostMediaStore(imageDir, voiceDir, avatarDir)

        val event = HostReadCommands.assistantsEvent(
            assistants = listOf(
                AssistantEntity(id = "default", name = "Muse", avatarImageUrl = "file://${avatar.absolutePath}"),
                AssistantEntity(id = "second", name = "Narrator"),
            ),
            defaultAssistantId = "default",
            requestId = "req-2",
            mediaLinks = HostMediaLinks(store),
        )

        assertEquals(2, event.assistants.size)
        val first = event.assistants.first()
        assertEquals("default", first.id)
        assertEquals("Muse", first.name)
        assertTrue("头像应换成媒体路由: ${first.avatarUri}", first.avatarUri!!.startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertEquals(
            avatar.canonicalFile,
            store.resolve(first.avatarUri!!.removePrefix(HostMediaLinks.MEDIA_PATH_PREFIX)),
        )
        assertTrue(first.isDefault)
        // 无头像的助手用 null 表示,不编空字符串。
        assertNull(event.assistants[1].avatarUri)
        assertTrue(!event.assistants[1].isDefault)
    }

    @Test
    fun `assistant avatar that cannot be served falls back to null`() {
        val event = HostReadCommands.assistantsEvent(
            assistants = listOf(
                AssistantEntity(id = "http", name = "Remote", avatarImageUrl = "https://cdn.example.com/a.png"),
                AssistantEntity(id = "gone", name = "Gone", avatarImageUrl = "file://${File(avatarDir, "nope.png").absolutePath}"),
                // 系统内置头像：安卓资源地址，浏览器取不到。
                AssistantEntity(
                    id = "builtin",
                    name = "Builtin",
                    avatarImageUrl = "android.resource://io.zer0.muse/drawable/default_assistant_avatar",
                ),
            ),
            defaultAssistantId = null,
            requestId = "req-3",
            mediaLinks = mediaLinks(),
        )

        assertEquals("https://cdn.example.com/a.png", event.assistants[0].avatarUri)
        assertNull("文件不存在时给 null，前端退回首字", event.assistants[1].avatarUri)
        assertNull("内置资源地址浏览器取不到", event.assistants[2].avatarUri)
    }

    @Test
    fun `providers list returns an empty array and no selected model on an empty database`() {
        val event = HostReadCommands.providersEvent(emptyList(), selectedModelId = null, requestId = "req-3")

        assertEquals(HostReadCommands.TYPE_PROVIDERS, event.type)
        assertTrue(event.providers.isEmpty())
        assertNull(event.selectedModelId)
        assertNull(event.error)
    }

    @Test
    fun `providers list maps models and skips internal providers`() {
        val visible = ProviderConfig(
            id = "preset_openai",
            displayName = "OpenAI",
            enabled = true,
            models = listOf(Model(id = "gpt-4o", name = "GPT-4o", providerId = "preset_openai")),
        )
        val internal = ProviderConfig(id = "system_speech", displayName = "System Speech", hiddenFromSettings = true)

        val event = HostReadCommands.providersEvent(
            providers = listOf(visible, internal),
            selectedModelId = "gpt-4o",
            requestId = "req-4",
        )

        assertEquals(1, event.providers.size)
        assertEquals("OpenAI", event.providers.single().name)
        assertTrue(event.providers.single().enabled)
        assertEquals("gpt-4o", event.providers.single().models.single().id)
        assertEquals("GPT-4o", event.providers.single().models.single().name)
        assertEquals("gpt-4o", event.selectedModelId)
    }

    @Test
    fun `session detail for a missing id returns an error instead of throwing`() {
        val event = HostReadCommands.sessionDetailEvent(session = null, messageCount = 0, requestId = "req-5")

        assertEquals(HostReadCommands.TYPE_SESSION_DETAIL, event.type)
        assertEquals("req-5", event.requestId)
        assertEquals(HostReadCommands.ERROR_SESSION_NOT_FOUND, event.error)
        assertNull(event.sessionDetail)
    }

    @Test
    fun `session detail carries metadata and message count`() {
        val session = SessionEntity(
            id = "session-1",
            title = "测试会话",
            createdAt = 100L,
            updatedAt = 200L,
            assistantId = "default",
            archived = true,
        )

        val event = HostReadCommands.sessionDetailEvent(session, messageCount = 7, requestId = "req-6")

        val detail = requireNotNull(event.sessionDetail)
        assertEquals("session-1", detail.id)
        assertEquals("测试会话", detail.title)
        assertEquals("default", detail.assistantId)
        assertTrue(detail.archived)
        assertEquals(100L, detail.createdAt)
        assertEquals(200L, detail.updatedAt)
        assertEquals(7, detail.messageCount)
        assertNull(event.error)
    }

    @Test
    fun `new payload fields survive the wire round trip`() {
        val event = HostReadCommands.sessionDetailEvent(
            SessionEntity(id = "s", title = "t", createdAt = 1L, updatedAt = 2L),
            messageCount = 3,
            requestId = "req-7",
        )

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertEquals(3, decoded.sessionDetail?.messageCount)
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
    }
}
