package io.zer0.muse.hook

import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * P1-1: Hook 注册表 — 管理所有 [SkillHook] 的注册/注销/执行。
 *
 * 设计:
 *  - 线程安全: 使用 [ConcurrentHashMap] 存储 Hook
 *  - 优先级排序: 按 priority 降序执行(数值越大越先执行)
 *  - 容错: 单个 Hook 抛异常不阻断链路,捕获并记录后继续
 *  - 类型安全: 按 Hook 类型(KClass)查询,避免类型转换错误
 *
 * 用法:
 * ```
 * val registry = HookRegistry()
 * registry.register(MyWorldBookHook())
 *
 * // 执行某类 Hook
 * val appended = registry.execute(
 *     SystemPromptComposeHook::class,
 *     initial = "",
 * ) { hook, acc ->
 *     acc + hook.afterComposeSystemPrompt(context)
 * }
 * ```
 */
class HookRegistry {

    private val TAG = "HookRegistry"

    /** 按 Hook 类型分桶存储,同一类型下按 id 去重。 */
    private val buckets = ConcurrentHashMap<KClass<out SkillHook>, MutableMap<String, SkillHook>>()

    /**
     * 注册 Hook。同 id 的 Hook 会被覆盖。
     * @param hook 要注册的 Hook 实例
     */
    fun register(hook: SkillHook) {
        val bucket = buckets.getOrPut(hook::class) { ConcurrentHashMap() }
        bucket[hook.id] = hook
        Logger.i(TAG, "registered: ${hook::class.simpleName}#${hook.id} (priority=${hook.priority})")
    }

    /**
     * 注销 Hook。
     * @param hook 要注销的 Hook 实例
     */
    fun unregister(hook: SkillHook) {
        buckets[hook::class]?.remove(hook.id)
        Logger.i(TAG, "unregistered: ${hook::class.simpleName}#${hook.id}")
    }

    /**
     * 按 id 注销指定类型的 Hook。
     */
    fun unregister(type: KClass<out SkillHook>, id: String) {
        buckets[type]?.remove(id)
    }

    /**
     * 获取指定类型的所有已启用 Hook(按 priority 降序排序)。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : SkillHook> getHooks(type: KClass<T>): List<T> {
        val bucket = buckets[type] ?: return emptyList()
        return bucket.values
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .map { it as T }
    }

    /**
     * 执行指定类型的 Hook 链。
     *
     * 每个 Hook 接收前一个 Hook 的输出作为输入,返回新的输出。
     * 单个 Hook 抛异常(非 CancellationException)不阻断链路,记录日志后跳过该 Hook。
     *
     * @param type Hook 类型
     * @param initial 初始值
     * @param block 对每个 Hook 执行的函数,接收 (hook, accumulator) 返回新的 accumulator
     * @return 最终的累加结果
     */
    suspend fun <T : SkillHook, R> execute(
        type: KClass<T>,
        initial: R,
        block: suspend (hook: T, acc: R) -> R,
    ): R {
        val hooks = getHooks(type)
        if (hooks.isEmpty()) return initial
        var acc = initial
        for (hook in hooks) {
            acc = try {
                block(hook, acc)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e(TAG, "Hook ${hook::class.simpleName}#${hook.id} failed, skipping: $summary", err)
                acc  // 失败则跳过,保留前一步结果
            }
        }
        return acc
    }

    /**
     * 执行无返回值的 Hook 链(仅副作用,如通知/日志)。
     */
    suspend fun <T : SkillHook> executeNoResult(
        type: KClass<T>,
        block: suspend (hook: T) -> Unit,
    ) {
        val hooks = getHooks(type)
        for (hook in hooks) {
            try {
                block(hook)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Error) {
                throw e
            } catch (err: Exception) {
                val summary = "${err::class.simpleName}: ${err.message?.take(200)}"
                Logger.e(TAG, "Hook ${hook::class.simpleName}#${hook.id} failed, skipping: $summary", err)
            }
        }
    }

    /** 清除所有注册的 Hook。 */
    fun clear() {
        buckets.clear()
        Logger.i(TAG, "all hooks cleared")
    }

    /** 获取已注册的 Hook 总数(调试用)。 */
    fun size(): Int = buckets.values.sumOf { it.size }
}
