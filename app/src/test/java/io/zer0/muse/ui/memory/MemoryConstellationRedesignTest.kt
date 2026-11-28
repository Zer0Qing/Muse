package io.zer0.muse.ui.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MemoryConstellationRedesignTest {

    private fun sampleNodes(count: Int): List<MemoryGraphNode> {
        val cats = listOf("identity", "preference", "relationship", "event", "goal", "medical")
        return (1L..count.toLong()).map { id ->
            MemoryGraphNode(
                factId = id,
                title = "记忆节点 $id",
                category = cats[(id % cats.size).toInt()],
                importance = (id % 3).toInt(),
                confidence = 0.9f,
                isPinned = id % 13L == 0L,
                isExpired = false,
            )
        }
    }

    @Test
    fun buildClusterConstellationIsDeterministicWithinBoundsAndAvoidsStarOverlap() {
        val nodes = sampleNodes(120)
        val sizeDp = 2400f
        val first = buildClusterConstellation(nodes, sizeDp)
        val second = buildClusterConstellation(nodes, sizeDp)

        assertEquals(first, second)
        assertEquals(nodes.size, first.nodeCenters.size)
        assertEquals(6, first.clusters.size)

        first.nodeCenters.values.forEach { pt ->
            assertTrue("x=${pt.x} out of bounds", pt.x in 90f..(sizeDp - 90f))
            assertTrue("y=${pt.y} out of bounds", pt.y in 90f..(sizeDp - 90f))
        }

        val coords = first.nodeCenters.entries.toList()
        for (i in coords.indices) {
            for (j in i + 1 until coords.size) {
                val a = coords[i].value
                val b = coords[j].value
                val dist = hypot(a.x - b.x, a.y - b.y)
                assertTrue("Stars ${coords[i].key} and ${coords[j].key} too close: dist=$dist", dist >= 36f)
            }
        }
    }

    @Test
    fun clusterMstSkeletonConnectsEveryMemberWithExactSpanningEdgeCount() {
        val nodes = sampleNodes(30) // 6 categories × 5 members each
        val constellation = buildClusterConstellation(nodes, 1600f)

        // 6 clusters of 5 nodes each -> 6 * (5 - 1) = 24 spanning skeleton edges
        assertEquals(24, constellation.skeletonEdges.size)
        val nodeCats = nodes.associate { it.factId to it.category }
        constellation.skeletonEdges.forEach { (a, b) ->
            assertEquals("Skeleton edge must stay within same category", nodeCats[a], nodeCats[b])
        }
    }

    @Test
    fun placeConstellationLabelsGuaranteesZeroOverlapsAndRespectsPriority() {
        val candidates = listOf(
            LabelCandidate(sampleNodes(1).first().copy(factId = 1), 200f, 200f, 12f, 90f, 26f, priority = 100),
            LabelCandidate(sampleNodes(1).first().copy(factId = 2), 205f, 202f, 12f, 90f, 26f, priority = 5000),
            LabelCandidate(sampleNodes(1).first().copy(factId = 3), 210f, 204f, 12f, 90f, 26f, priority = 300),
            LabelCandidate(sampleNodes(1).first().copy(factId = 4), 520f, 520f, 10f, 80f, 26f, priority = 50),
        )
        val obstacle = ScreenLabelBox(id = -1L, left = 220f, top = 185f, right = 330f, bottom = 220f)
        val placed = placeConstellationLabels(candidates, listOf(obstacle), maxCount = 10)

        assertTrue(placed.any { it.node.factId == 2L })
        for (i in placed.indices) {
            val boxA = placed[i].box
            for (j in i + 1 until placed.size) {
                val boxB = placed[j].box
                val overlap = boxA.left < boxB.right && boxB.left < boxA.right &&
                    boxA.top < boxB.bottom && boxB.top < boxA.bottom
                assertTrue("Placed labels overlapped: $boxA vs $boxB", !overlap)
            }
        }
    }

    @Test
    fun focusBoundsForCategoryCoversAllMembers() {
        val nodes = sampleNodes(30)
        val constellation = buildClusterConstellation(nodes, 1600f)
        val bounds = focusBoundsForCategory(constellation, nodes, "preference")

        assertNotNull(bounds)
        val b = bounds!!
        nodes.filter { it.category == "preference" }.forEach { node ->
            val p = constellation.nodeCenters.getValue(node.factId)
            assertTrue(p.x in b.minX..b.maxX)
            assertTrue(p.y in b.minY..b.maxY)
        }
    }
}
