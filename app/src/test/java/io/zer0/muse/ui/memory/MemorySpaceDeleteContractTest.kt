package io.zer0.muse.ui.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「删除记忆空间」三个会静默退化的点：
 *
 *  1. 确认弹窗不传 consequence → 退回通用「此操作不可撤销」，把「事实和摘要都还在」
 *     说成不可挽回，用户会以为删空间等于删记忆。
 *  2. 删掉的正好是当前选中空间 → `currentSpaceId` 仍指向一个已不存在的 id，
 *     记忆页按它过滤后列表直接变空；事实其实已合并进默认空间，但界面看上去像清空了。
 *  3. 文案只说「事实已迁回」→ 摘要被合并这件事没有任何交代，用户不敢删；
 *     而「已合并」与「已迁回」是两种不同的承诺。
 *
 * 三处失效都不会抛异常、不会编译失败，只能靠源码/资源断言钉住。
 * 没有驱动 ViewModel/Space 仓库：后者要真实的 Room + 默认空间初始数据，
 * 而这里要防的是「一行参数/一行判定被顺手删掉」。
 */
class MemorySpaceDeleteContractTest {

    private fun source(rel: String): String {
        val candidates = listOf(File(rel), File("app/$rel"), File("../app/$rel"))
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 $rel，工作目录=${File(".").absolutePath}"
        }.readText()
    }

    private fun uiSource() = source("src/main/java/io/zer0/muse/ui/memory/MemorySpaceSwitcher.kt")

    private fun viewModelSource() = source("src/main/java/io/zer0/muse/ui/memory/MemorySpaceViewModel.kt")

    private fun strings(qualifier: String) = source("src/main/res/$qualifier/strings_audit_memory_groups.xml")

    @Test
    fun `删除确认必须传入后果说明`() {
        val ui = uiSource()
        assertTrue(
            "删除 Space 的确认弹窗必须传 consequence，否则退回通用「此操作不可撤销」",
            ui.contains("consequence = stringResource(R.string.audit_memory_space_delete_consequence)"),
        )
    }

    @Test
    fun `删除成功后若删的是当前空间就切回默认空间`() {
        val vm = viewModelSource()
        val deleteAt = vm.indexOf("spaceRepository.deleteSpace(id)")
        val readAt = vm.indexOf("settings.currentSpaceIdFlow.first()")
        val saveAt = vm.indexOf("settings.saveCurrentSpaceId(MemorySpaceEntity.DEFAULT_SPACE_ID)")

        assertTrue("删除后必须读一次当前空间（删除之前读的可能是旧值之外的东西，顺序也会串）", deleteAt >= 0 && readAt > deleteAt)
        assertTrue("读到当前空间后才可能写回默认空间", saveAt > readAt)
        assertTrue("比较对象必须是被删的 id", vm.contains("if (currentSpaceId == id)"))
        assertTrue("调用 first() 需要 import kotlinx.coroutines.flow.first", vm.contains("import kotlinx.coroutines.flow.first"))
    }

    @Test
    fun `失败分支保留真实原因`() {
        val vm = viewModelSource()
        assertTrue("异常原因要落到提示里，不能一律显示成默认空间不可删除", vm.contains("_operationMessage.value = failureMessage"))
        assertTrue("仓库返回 false（默认空间/不存在）时仍要有那句专属文案", vm.contains("R.string.audit_memory_space_default_not_deletable"))
    }

    @Test
    fun `三种语言的删除文案都说合并而不是迁回`() {
        val zh = strings("values")
        val en = strings("values-en")
        val es = strings("values-es")

        listOf("values" to zh, "values-en" to en, "values-es" to es).forEach { (dir, xml) ->
            assertTrue(
                "$dir 缺 audit_memory_space_delete_consequence（缺了不会编译报错，弹窗会退回通用文案）",
                xml.contains("<string name=\"audit_memory_space_delete_consequence\">"),
            )
            assertTrue("$dir 缺 audit_memory_space_deleted", xml.contains("<string name=\"audit_memory_space_deleted\">"))
        }

        assertTrue("中文后果说明要讲清合并到默认空间", zh.contains("合并到默认空间"))
        assertTrue("中文后果说明要提到整理回滚点会清除", zh.contains("整理回滚点"))
        assertTrue("中文后果说明要说明事实和摘要保留", zh.contains("保留"))
        assertFalse("中文不再说「事实已迁回」，那只是事实层面", zh.contains("事实已迁回"))
        assertFalse("英文不再说 moved back", en.contains("moved back"))
        assertFalse("西语不再说 se han trasladado", es.contains("se han trasladado"))
    }
}
