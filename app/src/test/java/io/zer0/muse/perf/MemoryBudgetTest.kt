package io.zer0.muse.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存预算分配的自洽性检查。
 *
 * 这些断言不验证「Android 实际给了多少堆」（那由 ROM 决定，跑在 JVM 单测里也拿不到），
 * 只验证「拿到一个堆上限后，三套缓存怎么分」这套算法本身没有算错、
 * 不会出现总和超预算、也不会小到失去意义。
 */
class MemoryBudgetTest {

    /** 三套缓存的份额之和必须不超过总预算，否则等于没做预算。 */
    @Test
    fun `cache shares never exceed total image budget`() {
        val total = MemoryBudget.imageCacheBytes
        val sum = MemoryBudget.dataUriBitmapBytes + MemoryBudget.base64StringBytes + MemoryBudget.coilBytes
        assertTrue(
            "三套缓存合计 $sum 不应超过总预算 $total",
            sum <= total,
        )
    }

    /** 份额之和应接近总预算（允许取整损失），否则说明有份额漏配。 */
    @Test
    fun `cache shares use nearly all of the budget`() {
        val total = MemoryBudget.imageCacheBytes
        val sum = MemoryBudget.dataUriBitmapBytes + MemoryBudget.base64StringBytes + MemoryBudget.coilBytes
        val utilization = sum.toDouble() / total
        assertTrue(
            "预算利用率 $utilization 应接近 1（取整误差不超过 2%）",
            utilization > 0.98,
        )
    }

    /** 总预算必须有下限，否则低堆设备上缓存等于关掉。 */
    @Test
    fun `image budget respects floor`() {
        assertTrue(
            "图片预算 ${MemoryBudget.imageCacheBytes} 不应低于 8MB 下限",
            MemoryBudget.imageCacheBytes >= 8L * 1024 * 1024,
        )
    }

    /** 总预算必须有上限，否则超大堆设备上会占用过多内存。 */
    @Test
    fun `image budget respects ceiling`() {
        assertTrue(
            "图片预算 ${MemoryBudget.imageCacheBytes} 不应超过 64MB 上限",
            MemoryBudget.imageCacheBytes <= 64L * 1024 * 1024,
        )
    }

    /** data-URI 位图缓存拿最大份额：聊天内嵌图是滚动时最需要命中的对象。 */
    @Test
    fun `data uri cache gets the largest share`() {
        assertTrue(
            "data-URI 位图预算应大于 Coil 预算",
            MemoryBudget.dataUriBitmapBytes > MemoryBudget.coilBytes,
        )
        assertTrue(
            "data-URI 位图预算应大于 base64 预算",
            MemoryBudget.dataUriBitmapBytes > MemoryBudget.base64StringBytes,
        )
    }

    /** 堆上限必须读得到正数。 */
    @Test
    fun `heap max is positive`() {
        assertTrue("堆上限应为正数，实际 ${MemoryBudget.heapMaxMb}MB", MemoryBudget.heapMaxMb > 0)
    }

    /** 低堆判定与阈值一致：< 384MB 为低内存设备。 */
    @Test
    fun `low heap flag matches threshold`() {
        assertEquals(
            "isLowHeap 应与 heapMaxMb < 384 一致",
            MemoryBudget.heapMaxMb < 384,
            MemoryBudget.isLowHeap,
        )
    }
}
