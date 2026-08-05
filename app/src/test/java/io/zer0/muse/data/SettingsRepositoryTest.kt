package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
