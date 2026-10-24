package io.zer0.muse.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.R
import io.zer0.muse.backup.CloudBackupConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class SettingsRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    @Test
    fun enterGuestMode_keepsOnboardingNickname() = runBlocking {
        val repo = newRepository()
        repo.saveUserProfile(UserProfile(userNickName = "子奇"))

        repo.enterGuestMode()

        val state = repo.accountStateFlow.first()
        assertTrue(state.isGuestMode)
        assertEquals("子奇", state.userName)
    }

    @Test
    fun enterGuestMode_withoutNicknameFallsBackToGuest() = runBlocking {
        val repo = newRepository()

        repo.enterGuestMode()

        val state = repo.accountStateFlow.first()
        assertTrue(state.isGuestMode)
        assertEquals(context.getString(R.string.settings_repo_guest_name), state.userName)
    }

    @Test
    fun detectTaskType_emptyTextFallsBackToChat() {
        assertEquals(SettingsRepository.TaskType.CHAT, newRepository().detectTaskType(""))
    }

    @Test
    fun detectTaskType_codePromptDoesNotBecomeCreative() {
        assertEquals(SettingsRepository.TaskType.CODE, newRepository().detectTaskType("写代码实现一个排序算法"))
    }

    @Test
    fun detectTaskType_analysisPromptIsDetected() {
        assertEquals(SettingsRepository.TaskType.ANALYSIS, newRepository().detectTaskType("分析这份数据并给出报告"))
    }

    // ── P0-1: 备份密码标志(backupPasswordSet)不可被无关保存重算清空 ──

    @Test
    fun saveCloudBackupConfig_passwordBlankButFlagTrue_keepsFlag() = runBlocking {
        // Keystore 失效场景:密码读回为空,但用户曾设置过密码(标志为 true)。
        // 任何无关保存(如切 autoSync)都不得把标志重算为 false,否则云端/本地导出守卫失效。
        val repo = newRepository()
        repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "", backupPasswordSet = true))

        val readBack = repo.cloudBackupConfigFlow.first()

        assertTrue("密码失效后保存无关字段必须保留 backupPasswordSet=true", readBack.backupPasswordSet)
    }

    @Test
    fun saveCloudBackupConfig_passwordProvided_setsFlag() = runBlocking {
        // 用户新设置密码 → 标志置 true(加密走内存替身,Robolectric 无 Android Keystore)
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = object : SecureKeyCipher {
                override suspend fun encrypt(plain: String): String = "fake:$plain"
                override suspend fun decrypt(stored: String): String =
                    if (stored.startsWith("fake:")) stored.removePrefix("fake:") else stored
            }
            val repo = newRepository()
            repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "secret123", backupPasswordSet = false))

            val readBack = repo.cloudBackupConfigFlow.first()

            assertTrue("设置密码后 backupPasswordSet 必须为 true", readBack.backupPasswordSet)
        } finally {
            SecureKeyStore.delegate = original
        }
    }

    @Test
    fun saveCloudBackupConfig_passwordBlankAndFlagFalse_staysFalse() = runBlocking {
        // 用户显式清除密码(或从未设置)→ 标志保持 false,允许后续明文导出
        val repo = newRepository()
        repo.saveCloudBackupConfig(CloudBackupConfig(backupPassword = "", backupPasswordSet = false))

        val readBack = repo.cloudBackupConfigFlow.first()

        assertFalse("显式清除密码后 backupPasswordSet 必须为 false", readBack.backupPasswordSet)
    }

    // ── AUDIT §11.3-7: stringSet 类型设置项必须进备份 ──

    @Test
    fun stringSetFavoritesRoundTripThroughBackupSnapshot() = runBlocking {
        // 第二例同类缺口: 快照只遍历 string/bool/int/long 四类,stringSet 一个都没导出,
        // 朋友圈收藏与相册收藏换机即丢。这里跑完整往返:导出 → 改掉本地值 → 恢复 → 逐项比对。
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("moment-a", "moment-b"))
        repo.toggleMiniAlbumFavoriteImage("1024")
        repo.toggleMiniAlbumFavoriteImage("2048")

        val snapshot = repo.exportSettingsSnapshot()
        assertTrue(
            "朋友圈收藏必须进快照: ${snapshot.keys}",
            snapshot.containsKey("stringSet:moment_favorite_ids"),
        )
        assertTrue(
            "相册收藏必须进快照: ${snapshot.keys}",
            snapshot.containsKey("stringSet:mini_album_favorite_image_ids"),
        )

        // 模拟目标设备的现有状态(与备份内容无关)
        repo.saveMomentFavoriteIds(emptySet())
        repo.toggleMiniAlbumFavoriteImage("999")

        repo.restoreSettingsSnapshot(snapshot)

        assertEquals(
            "恢复后收藏内容必须一致",
            setOf("moment-a", "moment-b"),
            repo.momentFavoriteIdsFlow.first(),
        )
        assertEquals(
            "相册收藏恢复是覆盖而非合并:本地原有的 999 应被快照内容替掉",
            setOf("1024", "2048"),
            repo.miniAlbumFavoriteImageIdsFlow.first(),
        )
    }

    @Test
    fun emptyFavoriteSetSurvivesRoundTripAsEmptySet() = runBlocking {
        // 空集合"显式存在"与"没有这个 key"必须能区分:前者恢复后是空集合,
        // 后者应保持目标设备的原值不动。若编码用 joinToString,两者会压成一个空串。
        val repo = newRepository()
        repo.saveMomentFavoriteIds(emptySet())

        val snapshot = repo.exportSettingsSnapshot()
        assertEquals(
            "空集合也要写出这个 key(否则恢复时无法表达清空收藏)",
            "[]",
            snapshot["stringSet:moment_favorite_ids"],
        )
    }

    @Test
    fun oldBackupWithoutStringSetFieldsRestoresWithoutError() = runBlocking {
        // 旧备份(无 stringSet 字段)必须能正常恢复,且不得抹掉目标设备已有的收藏。
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("keep-me"))

        val oldStyleSnapshot = mapOf("theme_id" to "mono")
        repo.restoreSettingsSnapshot(oldStyleSnapshot)

        assertEquals(
            "快照里没这个 key 就不该动它(与其它四类同语义:只覆盖快照包含的 key)",
            setOf("keep-me"),
            repo.momentFavoriteIdsFlow.first(),
        )
    }

    @Test
    fun malformedStringSetValueIsSkippedInsteadOfWiped() = runBlocking {
        // 坏数据写空集会静默销毁收藏;正确做法是跳过这个 key。
        val repo = newRepository()
        repo.saveMomentFavoriteIds(setOf("keep-me"))

        repo.restoreSettingsSnapshot(mapOf("stringSet:moment_favorite_ids" to "not-a-json-array"))

        assertEquals(
            "解析失败应跳过而不是写空集",
            setOf("keep-me"),
            repo.momentFavoriteIdsFlow.first(),
        )
    }

    @Test
    fun nonWhitelistedStringSetIsIgnoredOnRestore() = runBlocking {
        // 恢复数据可能被篡改:未进白名单的 stringSet 不得写入(与四类标量同口径)。
        val repo = newRepository()
        repo.restoreSettingsSnapshot(mapOf("stringSet:daily_summary_claimed_slots" to "[\"2026-10-23#0900\"]"))

        assertFalse(
            "调度去重状态不得随备份恢复",
            repo.isDailySummarySlotClaimed("2026-10-23#0900"),
        )
    }
}

class SettingsRepositoryTestApplication : Application()
