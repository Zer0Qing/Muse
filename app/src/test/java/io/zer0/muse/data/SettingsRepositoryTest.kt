package io.zer0.muse.data

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
@Config(sdk = [33])
class SettingsRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository =
        SettingsRepository(context, mockk(relaxed = true))

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
}
