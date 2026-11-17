package io.zer0.muse.runtime

import io.zer0.muse.data.runtime.RuntimeInstallCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 安装卡状态与失败短码的纯函数测试。
 *
 * 这两张映射是卡片与诊断的唯一依据：映射错了，用户看到的要么是"还在装"其实是失败，
 * 要么是同一种失败在不同语言下变成不同短码。
 */
class RuntimeInstallCardStoreTest {

    @Test
    fun readyBeatsEverythingElse() {
        assertEquals(
            RuntimeInstallCardState.READY,
            RuntimeInstallCardStore.stateOf(
                progress = RuntimeInstaller.Progress.Downloading(downloaded = 1, total = 10),
                error = "上一轮的失败残留",
                ready = true,
            ),
        )
    }

    @Test
    fun errorBeatsInProgressStages() {
        assertEquals(
            RuntimeInstallCardState.FAILED,
            RuntimeInstallCardStore.stateOf(
                progress = RuntimeInstaller.Progress.Extracting(1024),
                error = "解压失败：truncated tar",
                ready = false,
            ),
        )
    }

    @Test
    fun eachStageMapsToItsOwnState() {
        val cases = listOf(
            RuntimeInstaller.Progress.Downloading(downloaded = 1, total = 10) to RuntimeInstallCardState.DOWNLOADING,
            RuntimeInstaller.Progress.Extracting(1) to RuntimeInstallCardState.EXTRACTING,
            RuntimeInstaller.Progress.Verifying to RuntimeInstallCardState.VERIFYING,
            RuntimeInstaller.Progress.SelfCheck to RuntimeInstallCardState.VERIFYING,
            RuntimeInstaller.Progress.Done to RuntimeInstallCardState.READY,
        )
        cases.forEach { (progress, expected) ->
            assertEquals(expected, RuntimeInstallCardStore.stateOf(progress = progress, error = null, ready = false))
        }
        assertEquals(RuntimeInstallCardState.OFFERED, RuntimeInstallCardStore.stateOf(progress = null, error = null, ready = false))
    }

    @Test
    fun failureMessagesCollapseToStableKinds() {
        assertNull(RuntimeInstallCardStore.errorKindOf(null))
        assertNull(RuntimeInstallCardStore.errorKindOf("   "))
        assertEquals("no_space", RuntimeInstallCardStore.errorKindOf("存储空间不够：还需要约 300 MB"))
        assertEquals("extract_failed", RuntimeInstallCardStore.errorKindOf("解压失败：tar 块对齐错误"))
        assertEquals("checksum_failed", RuntimeInstallCardStore.errorKindOf("sha256 校验不通过"))
        assertEquals("self_check_failed", RuntimeInstallCardStore.errorKindOf("环境装好了但跑不起来（node -v 失败）"))
        assertEquals("download_failed", RuntimeInstallCardStore.errorKindOf("下载中断"))
        assertEquals("unknown", RuntimeInstallCardStore.errorKindOf("unexpected boom"))
    }
}
