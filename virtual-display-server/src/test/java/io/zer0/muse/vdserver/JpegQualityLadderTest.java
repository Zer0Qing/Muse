package io.zer0.muse.vdserver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 截图质量阶梯（对比清单 P1-06「模型输入截图保真」）。
 *
 * 规则只有一条：**能高清就高清，超限才退档，且永远给出一张图**。
 * 截图拿不到会直接毁掉整步任务，而体积超一点只是传输慢一些。
 */
public class JpegQualityLadderTest {

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

        assertEquals("全都超限时仍要给最后一档", 60, chosen);
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
