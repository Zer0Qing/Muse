package io.zer0.muse.vdserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 截图质量阶梯（对比清单 P1-06「模型输入截图保真」）。
 *
 * 规则：**能高清就高清，超限先退质量、再退尺寸**。
 *
 * 原来这里写的是"永远给出一张图，体积超一点只是传输慢一些" —— 那句前提是错的：
 * `createDisplay` 允许到 1920x2560，最低质量仍可能超过 {@link JpegQualityLadder#MAX_BYTES}，
 * 而超尺寸的 byte[] 过 Binder 是**事务失败**，不是慢：客户端只会把异常当成"屏失效"去重建，
 * 然后同样失败。所以"始终给一张图"要成立，必须连尺寸一起退；怎么缩都超限时
 * 按契约返回 null（见 chooseScale 的用例）。
 */
public class JpegQualityLadderTest {

    @Test
    public void capStaysBelowTheBinderTransactionLimit() {
        // 整个设计的前提：MAX_BYTES 必须真的能过 Binder。
        // 有人为了"少退一档"把它调大，就会把"偶尔糊一点"变成"截图全废"。
        assertTrue(
                "单帧上限必须留出让给 Binder 事务头的余量（约 1MB）,实际 " + JpegQualityLadder.MAX_BYTES,
                JpegQualityLadder.MAX_BYTES < 1_000_000);
    }

    @Test
    public void picksTheHighestQualityWhenItFits() {
        int chosen = JpegQualityLadder.chooseQuality(quality -> 1000, JpegQualityLadder.MAX_BYTES);

        assertEquals(92, chosen);
    }

    @Test
    public void stepsDownOnlyAsFarAsNeeded() {
        // 92 太大、85 也太大、75 能放下 → 选 75
        int chosen = JpegQualityLadder.chooseQuality(
                quality -> quality >= 85 ? 2_000_000 : 500_000,
                JpegQualityLadder.MAX_BYTES);

        assertEquals(75, chosen);
    }

    @Test
    public void neverReturnsNothingEvenWhenEverythingIsTooBig() {
        int chosen = JpegQualityLadder.chooseQuality(quality -> Integer.MAX_VALUE, JpegQualityLadder.MAX_BYTES);

        assertEquals("全都超限时仍要给最后一档（之后交给缩尺寸那一层）", 60, chosen);
    }

    @Test
    public void scalePicksTheLargestThatFits() {
        // 0.75 还太大、0.6 能放下 → 选 0.6（不要一上来就缩到最小，模型要看清字）
        float scale = JpegQualityLadder.chooseScale(size -> size <= 0.6f ? 500_000 : 2_000_000,
                JpegQualityLadder.MAX_BYTES);

        assertEquals(0.6f, scale, 0.0001f);
    }

    @Test
    public void scaleReturnsZeroWhenNothingFits() {
        float scale = JpegQualityLadder.chooseScale(size -> Integer.MAX_VALUE, JpegQualityLadder.MAX_BYTES);

        assertEquals("怎么缩都超限时必须报 0，由调用方按契约返回 null", 0f, scale, 0.0001f);
    }

    @Test
    public void scaleStepsDescendAndStayUsable() {
        float previous = 1f;
        for (float scale : JpegQualityLadder.SCALE_STEPS) {
            assertTrue("缩放系数必须在 (0,1) 内: " + scale, scale > 0f && scale < 1f);
            assertTrue("阶梯必须递减", scale < previous);
            previous = scale;
        }
    }

    @Test
    public void theLadderIsStrictlyDescendingAndInRange() {
        for (int i = 0; i < JpegQualityLadder.QUALITIES.length; i++) {
            int quality = JpegQualityLadder.QUALITIES[i];
            assertTrue("质量必须在 1..100: " + quality, quality >= 1 && quality <= 100);
            if (i > 0) {
                assertTrue("阶梯必须严格递减", quality < JpegQualityLadder.QUALITIES[i - 1]);
            }
        }
    }

    @Test
    public void theProbeIsLazyAndStopsAtTheFirstFit() {
        final int[] calls = {0};
        JpegQualityLadder.chooseQuality(quality -> {
            calls[0]++;
            return 1000;
        }, JpegQualityLadder.MAX_BYTES);

        assertEquals("第一档就够小，不该继续编码", 1, calls[0]);
    }
}
