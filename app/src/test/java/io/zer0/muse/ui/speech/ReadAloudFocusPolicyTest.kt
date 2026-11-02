package io.zer0.muse.ui.speech

import android.media.AudioManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读的音频焦点策略（对比清单 P1-10）。
 *
 * 朗读最怕的不是"声音小"，是**来电时还在念**。这里的规则决定那一刻发生什么。
 */
class ReadAloudFocusPolicyTest {

    @Test
    fun `losing focus for good stops playback`() {
        assertEquals(
            ReadAloudAction.STOP,
            ReadAloudFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS),
        )
    }

    @Test
    fun `a transient loss pauses instead of stopping`() {
        assertEquals(
            ReadAloudAction.PAUSE,
            ReadAloudFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun `ducking also pauses because ducked speech is unintelligible`() {
        // 语音被压到 20% 之后基本听不清：与其让用户听一串糊掉的字，不如暂停后再继续
        assertEquals(
            ReadAloudAction.PAUSE,
            ReadAloudFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun `regaining focus continues`() {
        assertEquals(
            ReadAloudAction.CONTINUE,
            ReadAloudFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `unknown focus events do not interrupt playback`() {
        assertEquals(
            ReadAloudAction.CONTINUE,
            ReadAloudFocusPolicy.onFocusChange(-12345),
        )
    }

    @Test
    fun `the control channel delivers signals to the player`() = runTest {
        val received = mutableListOf<ReadAloudAction>()
        val job = launch { ReadAloudControl.signals.collect { received += it } }
        // 让收集器先订阅上，再发信号
        kotlinx.coroutines.yield()

        ReadAloudControl.emit(ReadAloudAction.PAUSE)
        ReadAloudControl.emit(ReadAloudAction.STOP)
        kotlinx.coroutines.yield()

        assertEquals(listOf(ReadAloudAction.PAUSE, ReadAloudAction.STOP), received)
        job.cancel()
    }
}
