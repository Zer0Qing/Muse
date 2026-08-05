package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillManagementToolsImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutRepository_returnsFriendlyErrors() = runBlocking {
        val impl = SkillManagementToolsImpl(context, skillRepository = null)
        val install = impl.installSkill(mapOf("skill_json" to "{}"))
        assertTrue(install.isNotBlank())

        val list = impl.listSkills(emptyMap())
        assertTrue(list.isNotBlank())

        val uninstall = impl.uninstallSkill(mapOf("id" to "x"))
        assertTrue(uninstall.isNotBlank())
    }
}
