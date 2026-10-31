package io.zer0.muse.ui.chat

/**
 * 当前对话上下占用量的对外只读入口。
 *
 * 占用量是 ViewModel 的内存态（每轮生成后重算），工具层在执行时只拿到
 * [io.zer0.muse.tools.ToolExecutionContext]，看不到 ChatUiState。这里提供一个
 * 进程级绑定点：由 ChatViewModel 启动时绑定一次（它是进程级单例），工具侧读到的
 * 就是占用圆环上那个数字，不会出现「工具报的占用率和界面不一致」。
 *
 * 未绑定（后台任务/未打开对话）时返回 null，调用方应整行省略而不是报 0。
 */
object ContextOccupancyProbe {

    @Volatile
    private var supplier: (() -> Pair<Int, Int>?)? = null

    /** 绑定读取器；后绑定的覆盖先绑定的（热重载/多实例时以最新对话为准）。 */
    fun bind(reader: () -> Pair<Int, Int>?) {
        supplier = reader
    }

    /** (当前占用, 上限)；未绑定或读取失败返回 null。 */
    fun current(): Pair<Int, Int>? = runCatching { supplier?.invoke() }.getOrNull()
}
