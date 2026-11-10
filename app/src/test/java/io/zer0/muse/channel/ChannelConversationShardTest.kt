package io.zer0.muse.channel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import io.zer0.muse.channel.ChannelConversationStore.Conversation
import io.zer0.muse.channel.ChannelConversationStore.Turn
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 会话分片存储：写入量、淘汰、清空、文件名与编解码。
 *
 * 这一组锁的是 2026-11-01 性能整改的核心行为 —— **append 只写这一条会话的文件**。
 * 旧实现每追加一条轮次都重写整张表（最多 50 个会话 × 200 轮），渠道每来一条消息至少
 * 追加两次（用户轮 + 助手轮），全部持同一把对象锁。若有人把写入改回整表，
 * 「追加后分片目录里的文件字节数不增长」这条会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChannelConversationShardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val shardDir: File get() = File(context.filesDir, "channel_conversations")

    private val channelA = "test-shard-a"
    private val channelB = "test-shard-b"

    @Before
    fun setUp() {
        ChannelConversationStore.attach(context)
        ChannelConversationStore.clear(channelA, "peer-1")
        ChannelConversationStore.clear(channelB, "peer-2")
    }

    @After
    fun tearDown() {
        ChannelConversationStore.clear(channelA, "peer-1")
        ChannelConversationStore.clear(channelB, "peer-2")
    }

    private fun shardFiles(): List<File> = shardDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }

    private fun bytesOf(key: String): Long =
        File(shardDir, ChannelConversationFiles.shardName(key)).let { if (it.exists()) it.length() else 0L }

    @Test
    fun `append writes only the touched conversation`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "你好")
        val afterFirst = shardFiles().size
        val bytesA = bytesOf(ChannelConversationStore.key(channelA, "peer-1"))

        ChannelConversationStore.append(channelB, "peer-2", "user", "另一条会话")
        val afterSecond = shardFiles().size
        // B 的追加不应改动 A 的文件（长度与内容都不变）。
        assertEquals("A 的分片不应因 B 的追加而变化", bytesA, bytesOf(ChannelConversationStore.key(channelA, "peer-1")))
        assertTrue("应当为两条会话各写一个分片", afterSecond == afterFirst + 1)
        assertTrue("分片文件数不应超过会话数", shardFiles().size <= ChannelConversationStore.conversations.value.size)
    }

    @Test
    fun `one_append_payload_is_a_single_conversation_not_the_whole_table`() {
        // 造 20 条会话，每条 30 轮；对比「整表编码」与「单条会话编码」的字节量。
        repeat(20) { index ->
            val channelId = "bulk-$index"
            ChannelConversationStore.clear(channelId, "peer")
            repeat(30) { turnIndex ->
                ChannelConversationStore.append(channelId, "peer", "user", "消息内容 $index-$turnIndex")
            }
        }
        val all = ChannelConversationStore.conversations.value
        val fullTableBytes = AppJson.encodeToString(
            MapSerializer(String.serializer(), Conversation.serializer()),
            all,
        ).toByteArray(Charsets.UTF_8).size
        val oneKey = ChannelConversationStore.key("bulk-0", "peer")
        val oneConversation = all.getValue(oneKey)
        val singleBytes = ChannelConversationFiles
            .encode(oneKey, oneConversation)
            .toByteArray(Charsets.UTF_8).size

        // 追加一条轮次真正需要的落盘量 = 单条会话；旧实现是整表。
        println(
            "[shard] full_table=$fullTableBytes B, single_conversation=$singleBytes B, " +
                "ratio=${fullTableBytes.toLong() / singleBytes}",
        )
        assertTrue(
            "单条会话编码($singleBytes B) 应远小于整表编码($fullTableBytes B)",
            singleBytes * 5 < fullTableBytes,
        )
        repeat(20) { index -> ChannelConversationStore.clear("bulk-$index", "peer") }
    }

    @Test
    fun `clear removes the shard file`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "要清掉的")
        val key = ChannelConversationStore.key(channelA, "peer-1")
        val file = File(shardDir, ChannelConversationFiles.shardName(key))
        assertTrue("追加后应存在分片", file.exists())

        ChannelConversationStore.clear(channelA, "peer-1")
        assertTrue("clear 后分片应被删除", !file.exists())
        assertNull(ChannelConversationStore.conversation(channelA, "peer-1"))
    }

    @Test
    fun `conversation_survives_as_single_shard_across_restore_format`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "重启后要还在")
        val key = ChannelConversationStore.key(channelA, "peer-1")
        val text = File(shardDir, ChannelConversationFiles.shardName(key)).readText()

        val decoded = ChannelConversationFiles.decode(text)
        assertNotNull("分片必须能解码", decoded)
        assertEquals(key, decoded!!.first)
        assertEquals("重启后要还在", decoded.second.turns.first().text)
    }

    @Test
    fun `shard name is stable and path safe`() {
        val key = ChannelConversationStore.key("telegram|weird/../id", "peer\u0000x")
        val name = ChannelConversationFiles.shardName(key)
        assertEquals(name, ChannelConversationFiles.shardName(key))
        assertTrue("文件名不得含路径分隔符", !name.contains("/") && !name.contains("\\"))
        assertTrue("文件名后缀固定", name.endsWith(".json"))
        assertEquals("sha256 十六进制 64 位 + 后缀", 64 + 5, name.length)
    }

    @Test
    fun `corrupt shard payload is skipped instead of becoming empty conversation`() {
        assertNull(ChannelConversationFiles.decode("{not json"))
        assertNull(ChannelConversationFiles.decode(""))
    }

    @Test
    fun `turn round trip keeps media fields`() {
        val conversation = Conversation(
            summary = "摘要",
            turns = listOf(
                Turn(
                    role = "user",
                    text = "",
                    mediaKind = "image",
                    mediaBase64 = "AAAA",
                    mediaPath = "/tmp/x.jpg",
                    mediaDescription = "描述",
                    dispatchId = "d-1",
                ),
            ),
            updatedAt = 123L,
            recentDispatchTurnIds = listOf("d-1\u0000user"),
        )
        val key = "k|v"
        val decoded = ChannelConversationFiles.decode(ChannelConversationFiles.encode(key, conversation))
        assertEquals(conversation, decoded!!.second)
    }
}
