package io.zer0.muse.ui.common.form

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F3.2 弹层拖拽释放决策的纯函数测试。
 *
 * 钉死四条边界语义（都是曾在内联 if 里被改错过的类型）：
 *  1. 位移达阈值 → 关闭；差 1px 都不关（阈值 inclusive 判定）；
 *  2. 轻弹：速度足够 + 位移过阈值三成 → 关闭；速度不足不关；
 *  3. 上滑（负位移）永远不触发关闭，哪怕速度向下也不按绝对值误判；
 *  4. 其余情况一律回弹。
 */
class MuseBottomSheetSettleTest {

    private val threshold = 100f

    @Test
    fun `drag beyond threshold dismisses`() {
        assertEquals(SheetSettleTarget.DISMISS, sheetSettleDecision(100f, 0f, threshold))
        assertEquals(SheetSettleTarget.DISMISS, sheetSettleDecision(320f, 100f, threshold))
    }

    @Test
    fun `just below threshold without fling settles back`() {
        assertEquals(SheetSettleTarget.SETTLE, sheetSettleDecision(99f, 100f, threshold))
    }

    @Test
    fun `fling closes when past thirty percent of threshold`() {
        assertEquals(SheetSettleTarget.DISMISS, sheetSettleDecision(35f, 900f, threshold))
        assertEquals(SheetSettleTarget.SETTLE, sheetSettleDecision(34f, 900f, threshold))
        assertEquals(SheetSettleTarget.SETTLE, sheetSettleDecision(80f, 799f, threshold))
    }

    @Test
    fun `upward drag never dismisses even with fast upward velocity`() {
        // 负方向=上滑越界（橡胶带区域）；向下速度为负也不该按绝对值误判成下拉关闭。
        assertEquals(SheetSettleTarget.SETTLE, sheetSettleDecision(-40f, -1200f, threshold))
        assertEquals(SheetSettleTarget.SETTLE, sheetSettleDecision(-120f, -50f, threshold))
    }
}
