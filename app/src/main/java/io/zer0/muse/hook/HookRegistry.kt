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
 *  - 分桶口径: 一律按 **接口** 分桶。注册时按实现类命中的接口逐个入桶,
 *    查询时按接口取,两侧口径一致;一个实现类可同时实现多个接口
 *    (如 WorldBookHook 同时是 SystemPromptComposeHook 与 PromptFinalizeHook)。
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

    /** 按 Hook 接口分桶存储,同一类型下按 id 去重。 */
    private val buckets = ConcurrentHashMap<KClass<out SkillHook>, MutableMap<String, SkillHook>>()

    /**
     * 全部已注册实例(与分桶并行维护)。
     *
     * 分桶依赖 [HOOK_TYPES] 登记,登记漏一行就会让对应接口的派发点永远取到空表且不报错。
     * 这里保留一份扁平索引,查询时按接口 isInstance 兜底,新增接口忘了登记也不会静默失效。
     */
    private val allHooks = ConcurrentHashMap<String, SkillHook>()

    /**
     * 注册 Hook。同一个实例会按其实现的全部 Hook 接口分别入桶,
     * 因此按任意一个接口查询都能取到。
     * @param hook 要注册的 Hook 实例
     */
    fun register(hook: SkillHook) {
        // 先进全量索引:即使它实现的接口没登记在 HOOK_TYPES,查询时也能兜底取到。
        allHooks[hook.id] = hook
        val types = hookTypesOf(hook)
        if (types.isEmpty()) {
            Logger.w(TAG, "register: ${hook::class.simpleName}#${hook.id} 的接口未登记，走全量兜底")
            return
        }
        types.forEach { type -> buckets.getOrPut(type) { ConcurrentHashMap() }[hook.id] = hook }
        Logger.i(
            TAG,
            "registered: ${hook::class.simpleName}#${hook.id} interfaces=${types.size} (priority=${hook.priority})",
        )
    }

    /**
     * 注销 Hook。按它实现的全部 Hook 接口逐个摘除。
     * @param hook 要注销的 Hook 实例
     */
    fun unregister(hook: SkillHook) {
        allHooks.remove(hook.id)
        hookTypesOf(hook).forEach { type -> buckets[type]?.remove(hook.id) }
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
        // 有桶就以桶为准(桶才是「按类型注销」的唯一凭据);
        // 没桶说明该接口未登记在 HOOK_TYPES,此时扫全量索引兜底。
        val bucket = buckets[type]
        val candidates = if (bucket != null) {
            bucket.values
        } else {
            allHooks.values.filter { type.java.isInstance(it) }
        }
        return candidates
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
    suspend fun <T : SkillHook, R> execute(type: KClass<T>, initial: R, block: suspend (hook: T, acc: R) -> R): R {
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
                acc // 失败则跳过,保留前一步结果
            }
        }
        return acc
    }

    /**
     * 执行无返回值的 Hook 链(仅副作用,如通知/日志)。
     */
    suspend fun <T : SkillHook> executeNoResult(type: KClass<T>, block: suspend (hook: T) -> Unit) {
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
        allHooks.clear()
        Logger.i(TAG, "all hooks cleared")
    }

    /** 获取已注册的 Hook 总数(按实例去重,调试用)。 */
    fun size(): Int = allHooks.size

    /** 该 Hook 实例对应的接口桶。注册与注销共用一份口径。 */
    private fun hookTypesOf(hook: SkillHook): List<KClass<out SkillHook>> =
        HOOK_TYPES.filter { it.java.isInstance(hook) }

    private companion object {
        /**
         * 全部 Hook 接口清单。
         *
         * 必须与 [SkillHook] 文件里声明的接口保持同步;新增 Hook 接口时在这里加一行,
         * 否则该接口的派发点会永远取到空表(且不会报错)。
         */
        val HOOK_TYPES: List<KClass<out SkillHook>> = listOf(
            AppLifecycleHook::class,
            MessageProcessingPlugin::class,
            SystemPromptComposeHook::class,
            ToolPromptComposeHook::class,
            PromptFinalizeHook::class,
            ToolLifecycleHook::class,
            SummaryGenerateHook::class,
        )
    }
}
