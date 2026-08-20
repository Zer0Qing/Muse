package io.zer0.muse.ui.memory

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConstellationLayoutTest {
    @Test
    fun layoutIsDeterministicAndKeepsNodeRectanglesSeparated() {
        listOf(40, 100, 500).forEach { count ->
            val nodes = (1L..count.toLong()).map { id ->
                MemoryGraphNode(
                    factId = id,
                    title = "fact-$id",
                    category = if (id % 2L == 0L) "identity" else "goal",
                    importance = if (id <= 2L) 2 else 0,
                    confidence = 1f,
                    isPinned = id == 1L,
                    isExpired = false,
                )
            }
            val first = buildConstellationCoordinates(nodes, 10000.dp, 10000.dp)
            val second = buildConstellationCoordinates(nodes, 10000.dp, 10000.dp)
            assertEquals(first, second)
            assertEquals(count, first.size)
            val rectangles = first.values.map { point ->
                point.x.value..(point.x.value + 170f) to point.y.value..(point.y.value + 68f)
            }
            for (i in rectangles.indices) {
                for (j in i + 1 until rectangles.size) {
                    val (ax, ay) = rectangles[i]
                    val (bx, by) = rectangles[j]
                    val overlap = ax.start < bx.endInclusive && bx.start < ax.endInclusive &&
                        ay.start < by.endInclusive && by.start < ay.endInclusive
                    assertTrue("node rectangles overlap for count=$count at $i/$j", !overlap)
                }
            }
        }
    }
}
