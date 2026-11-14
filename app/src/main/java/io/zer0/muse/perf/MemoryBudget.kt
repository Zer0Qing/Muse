package io.zer0.muse.perf

/**
 * 进程级内存预算。
 *
 * 为什么需要:图片相关缓存有三套(Coil 全局缓存、data-URI 位图缓存、
 * 消息 base64 字符串缓存),此前各自按堆比例定容、互不知情。
 * 在 256MB 堆的机型上三者合计可占到 95MB(37%),留给 Compose、请求体、
 * 渲染缓冲的空间不足,表现为随机线程的 OOM(协程调度器、Conscify TLS 写出、
 * 厂商 Jank 统计都可能是最后倒下的那个)。
 *
 * 这里把三套缓存的上限收进一处:先按设备堆大小判断是否低内存设备,
 * 再据此给出总图片预算,由各缓存按自己的职责分配。
 *
 * 上限来自 `Runtime.maxMemory()`,它由 ROM 的 `dalvik.vm.heapsize`(largeHeap)
 * 或 `heapgrowthlimit` 决定,应用无法修改 —— 所以这里只做分配,不做"调大"。
 */
object MemoryBudget {

    /** 堆上限(MB)。进程生命周期内不变,首次读取后缓存。 */
    val heapMaxMb: Int by lazy {
        (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
    }

    /**
     * 是否为低内存设备。
     *
     * 阈值取 384MB:低于此值时三套缓存即使按比例收缩,留给渲染的余量仍然紧张。
     * 常见取值参考:低端机 128/192,中端 256,较新机型 512。
     */
    val isLowHeap: Boolean by lazy { heapMaxMb < LOW_HEAP_THRESHOLD_MB }

    /**
     * 图片类缓存总预算(字节)。
     *
     * 低内存设备给堆的 12%,其余给 18%,并统一封顶 [IMAGE_BUDGET_CEILING_BYTES]。
     * 256MB 堆下:低内存分支得 30MB,比改动前的约 95MB 少三分之二。
     * 512MB 堆下得 92MB,封顶 64MB。
     */
    val imageCacheBytes: Long by lazy {
        val fraction = if (isLowHeap) LOW_HEAP_IMAGE_FRACTION else NORMAL_IMAGE_FRACTION
        val raw = (Runtime.getRuntime().maxMemory() * fraction).toLong()
        raw.coerceAtMost(IMAGE_BUDGET_CEILING_BYTES).coerceAtLeast(IMAGE_BUDGET_FLOOR_BYTES)
    }

    /**
     * data-URI 位图缓存预算(字节)。
     *
     * 取总预算的 55%:聊天内嵌图是滚动时最需要命中的对象,给大头。
     * 256MB 堆下约 16MB。
     */
    val dataUriBitmapBytes: Long by lazy { (imageCacheBytes * DATA_URI_SHARE).toLong() }

    /**
     * 消息 base64 字符串缓存预算(字节)。
     *
     * 取总预算的 30%。字符串在堆上的占用是字符数的 2 倍(char 为 2 字节),
     * 所以同样字节预算下它实际能装的图片比位图缓存少,给较小份额。
     */
    val base64StringBytes: Long by lazy { (imageCacheBytes * BASE64_SHARE).toLong() }

    /**
     * Coil 全局缓存预算(字节)。
     *
     * 剩余份额(15%):普通 URL/Uri 图片走 Coil,聊天内嵌图走 data-URI 缓存,
     * 后者更常用,所以 Coil 拿小头。
     */
    val coilBytes: Long by lazy { (imageCacheBytes * COIL_SHARE).toLong() }

    /** 低内存设备的堆上限阈值(MB)。 */
    private const val LOW_HEAP_THRESHOLD_MB = 384

    /** 低内存设备的图片预算占堆比例。 */
    private const val LOW_HEAP_IMAGE_FRACTION = 0.12

    /** 常规设备的图片预算占堆比例。 */
    private const val NORMAL_IMAGE_FRACTION = 0.18

    /** 图片预算绝对上限:再大的堆也没必要为图片留更多。 */
    private const val IMAGE_BUDGET_CEILING_BYTES = 64L * 1024 * 1024

    /** 图片预算下限:低于此值时缓存基本失去意义,不如直接关掉。 */
    private const val IMAGE_BUDGET_FLOOR_BYTES = 8L * 1024 * 1024

    private const val DATA_URI_SHARE = 0.55
    private const val BASE64_SHARE = 0.30
    private const val COIL_SHARE = 0.15
}
