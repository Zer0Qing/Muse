package io.zer0.muse.vdserver;

/**
 * 截图 JPEG 质量的**阶梯**（对比清单 P1-06「模型输入截图保真」）。
 *
 * 原来的写法是"先用 80，超过 900KB 再降到 60"：一上来就牺牲了文字清晰度，
 * 而模型要靠这张图认按钮和数字 —— 80 对细笔画和小字号本来就偏糊，
 * 一旦真的超过上限又直接掉到 60，落差太大。
 *
 * 现在改成从高往低退：先按 92 编，超限才逐档下降。
 * 大多数界面在 92 下就够小，只有长列表/照片墙这类才会退档。
 *
 * 这里只负责"选哪一档"，编码本身仍在调用方 —— 这样选择规则可以在没有设备的环境里验证。
 */
final class JpegQualityLadder {

    /** 从高到低；最后一档是兜底（宁可糊一点，也不能因为超限就不给图）。 */
    static final int[] QUALITIES = {92, 85, 75, 60};

    /** 单帧体积上限：与既有护栏保持一致。 */
    static final int MAX_BYTES = 900_000;

    private JpegQualityLadder() {
    }

    /** 让调用方按质量试编码并返回字节数（惰性：只有需要时才真的编码）。 */
    interface SizeProbe {
        int sizeAt(int quality);
    }

    /**
     * 选出第一个不超过上限的质量；全都超限时返回最后一档。
     *
     * **不返回"失败"**：截图拿不到会直接毁掉整步任务，而体积超一点只是传输慢一些。
     */
    static int chooseQuality(SizeProbe probe, int maxBytes) {
        for (int quality : QUALITIES) {
            if (probe.sizeAt(quality) <= maxBytes) {
                return quality;
            }
        }
        return QUALITIES[QUALITIES.length - 1];
    }
}
