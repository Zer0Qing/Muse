package io.zer0.muse.ui.settings

import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高级 TTS 参数的"有没有未保存草稿"判定。
 *
 * 这个布尔值决定离开媒体设置页时会不会弹放弃确认:漏判 = 用户白填,误判 = 每次返回
 * 都被拦一次。两种都算缺陷,所以把口径固定在这里测住。
 */
class MediaTtsDraftDirtyTest {

    private val saved = MediaConfig(
        ttsStability = 0.5f,
        ttsSimilarityBoost = 0.75f,
        ttsEmotion = "happy",
        ttsCloudSpeed = 1.0f,
        ttsResponseFormat = "mp3",
    )

    @Test
    fun `draft equal to saved is not dirty`() {
        assertFalse(
            advancedTtsDraftDirty(
                config = saved,
                stability = 0.5f,
                similarity = 0.75f,
                emotion = "happy",
                speed = 1.0f,
                responseFormat = "mp3",
            ),
        )
    }

    @Test
    fun `only whitespace around emotion is not a change`() {
        assertFalse(
            advancedTtsDraftDirty(
                config = saved,
                stability = 0.5f,
                similarity = 0.75f,
                emotion = "  happy ",
                speed = 1.0f,
                responseFormat = "mp3",
            ),
        )
    }

    @Test
    fun `any single field drift is dirty`() {
        assertTrue(
            advancedTtsDraftDirty(
                config = saved,
                stability = 0.6f,
                similarity = 0.75f,
                emotion = "happy",
                speed = 1.0f,
                responseFormat = "mp3",
            ),
        )
        assertTrue(
            advancedTtsDraftDirty(
                config = saved,
                stability = 0.5f,
                similarity = 0.75f,
                emotion = "happy",
                speed = 1.0f,
                responseFormat = "opus",
            ),
        )
    }
}
