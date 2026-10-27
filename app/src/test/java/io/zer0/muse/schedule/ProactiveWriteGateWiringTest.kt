package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 架构守卫：主动消息巡检必须在碰数据之前先看恢复闸门。
 *
 * 备份恢复期间数据库正在被整库替换，这时候写主动消息会写进一个马上要被覆盖的库，
 * 表现是「恢复完成后凭空多出一条三天前的消息」。这条约束只能靠读源码来守 ——
 * 它的失败模式是数据被污染，不是抛异常。
 */
class ProactiveWriteGateWiringTest {

    @Test
    fun `proactive cycle checks restore gate before doing anything else`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/proactive/ProactiveCycleRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/proactive/ProactiveCycleRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ProactiveCycleRunner.kt not found")

        // 闸门必须出现在准备阶段，且排在读助手/读会话之前
        val prepareRegion = source.substringAfter("private suspend fun gateBlock")
            .substringBefore("private suspend fun assemble")
        assertTrue(
            "恢复闸门必须在准备阶段检查",
            prepareRegion.contains("ProcessWriteGate.restoring"),
        )

        val assembleIndex = source.indexOf("private suspend fun assemble")
        val gateIndex = source.indexOf("ProcessWriteGate.restoring")
        assertTrue(
            "恢复闸门必须早于材料装载，否则会读到正在被替换的库",
            gateIndex in 0 until assembleIndex,
        )
    }
}
