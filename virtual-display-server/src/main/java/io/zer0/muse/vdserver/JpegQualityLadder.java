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

    /**
     * 质量退到底仍超限时，再往下缩尺寸（2026-11-04 补）。
     *
     * 原来这里有个自相矛盾的地方：注释说"宁可糊一点也要给图，因为拿不到图会毁掉整步任务"，
     * 可 `createDisplay` 允许到 1920x2560 —— 那种帧在最低质量下仍可能超过
     * {@link #MAX_BYTES}，而超尺寸的 byte[] 过 Binder 会直接事务失败，
     * 客户端只会把异常当成"屏失效"去重建兼容屏，然后同样失败 ——
     * 结果既没给到图，还多烧掉一次重建。
     * 所以"始终给一张图"要成立，必须连尺寸一起退。
     */
    static final float[] SCALE_STEPS = {0.75f, 0.6f, 0.5f};

    /** 缩放后的体积探针（与 {@link SizeProbe} 同理：编码由调用方做，这里只做判断）。 */
    interface ScaledSizeProbe {
        int sizeAt(float scale);
    }

    /**
     * 选出第一个能放进上限的缩放系数；怎么缩都超限时返回 `0f`，
     * 由调用方按契约放弃（返回 null）而不是把超限帧硬塞进 Binder。
     */
    static float chooseScale(ScaledSizeProbe probe, int maxBytes) {
        for (float scale : SCALE_STEPS) {
            if (probe.sizeAt(scale) <= maxBytes) {
                return scale;
            }
        }
        return 0f;
    }
}
