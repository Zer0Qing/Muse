package io.zer0.muse.data.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 35: 用户面具库的落盘与每助手选择。
 *
 * 跑真 DataStore（Robolectric 临时应用目录），验「写进去读回来还在」、
 * 每助手选择互不串、删面具时解除悬空选择。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserPersonaStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = DataStoreUserPersonaStore(context)

    @Test
    fun upsertAndReadBack() = runTest {
        val s = store()
        s.upsert(UserPersona("p1", "侠客", "我是一个江湖侠客"))
        val list = s.personas()
        assertEquals(1, list.size)
        assertEquals("侠客", list.first().name)
        assertEquals("我是一个江湖侠客", s.personasFlow.first().first().text)
    }

    @Test
    fun selectionIsPerAssistant() = runTest {
        val s = store()
        s.upsert(UserPersona("p1", "甲", "设定甲"))
        s.upsert(UserPersona("p2", "乙", "设定乙"))
        s.select("assistant-a", "p1")
        s.select("assistant-b", "p2")

        assertEquals("p1", s.selectedFor("assistant-a")?.id)
        assertEquals("p2", s.selectedFor("assistant-b")?.id)
        assertNull("未选择的助手应回落全局", s.selectedFor("assistant-c"))
    }

    @Test
    fun deletingPersonaClearsDanglingSelection() = runTest {
        val s = store()
        s.upsert(UserPersona("p1", "甲", "设定甲"))
        s.select("assistant-a", "p1")
        assertEquals("p1", s.selectedFor("assistant-a")?.id)

        s.delete("p1")

        assertTrue(s.personas().isEmpty())
        assertNull("删掉的面具不应再被选中", s.selectedFor("assistant-a"))
    }

    @Test
    fun selectNullFallsBackToGlobal() = runTest {
        val s = store()
        s.upsert(UserPersona("p1", "甲", "设定甲"))
        s.select("assistant-a", "p1")
        s.select("assistant-a", null)
        assertNull(s.selectedFor("assistant-a"))
    }
}
