package io.zer0.muse.data.plugin

import io.zer0.common.Logger
import java.io.File
import java.security.MessageDigest

/**
 * 插件能力总线（v2 重构 P0-1/P0-2）：把"清单声明了什么"变成"运行时到底放不放行"。
 *
 * ## 为什么要它
 *
 * 之前 `SkillBridge` 的调用方无条件传 `ALL_ACTIONS`，而安装确认页却对用户列出"声明能力"
 * （`PluginManagePage.kt:2063`）—— 用户看到的能力清单与实际权限毫无关系：**声明只是装饰**。
 * 这比没有权限系统更糟，因为它制造了假安全感。
 *
 * ## 五条规则
 *
 * 1. **默认拒绝**：能力未授予 → 对应桥动作不可用（`echo` 除外，它不带任何外泄面）。
 * 2. **未登记的动作也拒绝**：新加桥动作却忘了登记映射 = 谁都调不到，而不是"不需要权限"。
 * 3. **声明 ≠ 授权**：首见某插件时按已重验的声明批量落账（`decidedBy = install-auto`），
 *    与今天行为一致，存量插件不会突然坏。
 * 4. **升级扩权必须重批**：清单新增的能力不放行（见 [addedCapabilities]）；只缩小声明则静默跟随。
 * 5. **每次调用重新查账**：撤销立即生效，不缓存到"注册工具列表"那一刻。
 *
 * 持久授权（ALWAYS）落盘；一次性 / 会话性授权走 [PluginSessionGrants]，两者生命周期不同。
 * 能力名沿用清单已有词汇（`network` / `resource.read` / `resource.write` / `ui.skin`），
 * 新增项按 `组.动作` 命名，避免再造一套需要映射的同义词。
 */
class PluginCapabilityGateway(
    private val storeFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 判定结果。[Decision.Deny] 必须带"下一步怎么办"，让模型与用户都有路可走。 */
    sealed interface Decision {
        data object Allow : Decision

        data class Deny(val capability: String, val reason: String) : Decision
    }

    @Volatile
    private var store: PluginPermissionStore = PluginPermissionStore.fromFile(storeFile)

    private val session = PluginSessionGrants()
    private val audit = PluginCapabilityAudit(now)

    /**
     * 该插件当前可用的桥动作集合。
     *
     * [declared] 必须来自**磁盘重验后的 manifest**（`SkillMediaToolsImpl.kt:181` 已是该口径），
     * 不能取注册表缓存字段 —— 否则改一下包内清单就能绕过授权。
     */
    fun allowedActions(pluginId: String, declared: List<String>): Set<String> =
        actionsOf(grantedCapabilities(pluginId, declared))

    /** 只查账、不看清单：给拿不到已重验 manifest 的执行点用（Hook、激活、沙盒网络出口）。 */
    fun approvedActions(pluginId: String): Set<String> = actionsOf(liveCapabilities(pluginId))

    /** 某能力是否已批准（沙盒网络出口与 Python 档这类单点判断用；会消费 ONCE 档的一次额度）。 */
    fun isApproved(pluginId: String, capability: String): Boolean {
        // Python 档走"记账本不看声明"这条路：清单里写多少次 runtime.python 都不会自我授予，
        // 只有用户通过 grant 落的那条记录算数。
        if (capability == RUNTIME_PYTHON) return store.plugins[pluginId]?.grants?.get(capability)?.allowed == true
        val canonical = CAPABILITY_ALIASES[capability] ?: capability
        return session.take(pluginId, canonical) || canonical in liveCapabilities(pluginId)
    }

    /** 单个动作判定；放行与拒绝都记审计。 */
    fun check(pluginId: String, declared: List<String>, action: String): Decision {
        // 安全模式总闸：连 echo 都不放行 —— "全部停用"不该留任何例外。
        val disabled = PluginKillSwitch.isDisabledBeside(storeFile)
        val capability = CAPABILITY_FOR_ACTION[action]
        // 共享空间动作族的授权由"清单里声明了哪个名字空间"决定，不在能力表里。
        // 具体名字空间是否合法、是否真的声明了它，由桥接层再判一次（那里拿得到 params）。
        val sharedStorageAllowed = action in SHARED_STORAGE_ACTIONS &&
            declared.any { it.trim().startsWith(PluginSharedStorage.CAPABILITY_PREFIX) }
        // 先消费会话额度：ONCE 档必须"用完即失效"。若先查持久集合，
        // liveCapabilities 会把还剩余额的 ONCE 也算进去，于是永远用不完。
        val allowed = !disabled && (
            action in ALWAYS_ALLOWED_ACTIONS ||
                sharedStorageAllowed ||
                (capability != null &&
                    (session.take(pluginId, capability) || capability in grantedCapabilities(pluginId, declared)))
            )
        return when {
            allowed -> {
                if (capability != null) audit.record(pluginId, action, capability, allowed = true)
                Decision.Allow
            }
            disabled -> deny(pluginId, action, "", PluginKillSwitch.DISABLED_MESSAGE)
            action in SHARED_STORAGE_ACTIONS -> deny(
                pluginId,
                action,
                "-",
                "清单未声明任何共享空间：需要 capabilities 里写 \"storage.shared:<名字>\"",
            )
            capability == null -> deny(pluginId, action, "-", UNSUPPORTED_ACTION)
            capability !in declared -> deny(
                pluginId,
                action,
                capability,
                "插件清单未声明能力 '$capability'，因此不能调用 '$action'；需要该功能请更新清单声明后重新安装确认。",
            )
            else -> deny(
                pluginId,
                action,
                capability,
                "能力 '$capability' 未授权（动作 '$action' 被拒）；" +
                    "可在 设置 → 工具与扩展 → 插件 → 权限 中授予，或改用不需要该能力的做法。",
            )
        }
    }

    /**
     * 授予/撤销一项能力。
     *
     * [tier] 决定持久性：[PluginPermissionTier.ALWAYS] 落盘；ONCE 只放行一次；SESSION 本次进程内有效。
     */
    fun grant(
        pluginId: String,
        capability: String,
        allowed: Boolean,
        declared: List<String>,
        tier: PluginPermissionTier = PluginPermissionTier.ALWAYS,
        decidedBy: String = PluginCapabilityGrant.DECIDED_BY_USER,
    ) {
        if (tier == PluginPermissionTier.ALWAYS) {
            session.put(pluginId, capability, tier, allowed = false)
            writeGrant(pluginId, capability, allowed, declared, decidedBy)
        } else if (capability != RUNTIME_PYTHON) {
            session.put(pluginId, capability, tier, allowed)
        }
    }

    /** 批量接受一份新声明（用户在升级审核里点"同意"）。 */
    fun approveDeclaration(pluginId: String, declared: List<String>) {
        store = store.copy(
            plugins = store.plugins.withRecord(pluginId, declared) { record ->
                record.copy(
                    grants = declared.associateWith { capability ->
                        record.grants[capability] ?: PluginCapabilityGrant(
                            allowed = true,
                            decidedBy = PluginCapabilityGrant.DECIDED_BY_INSTALL_AUTO,
                            decidedAt = now(),
                        )
                    },
                )
            },
        )
        persist()
    }

    /** 撤销该插件全部授权（卸载/停用/清数据时调用）。 */
    fun revokeAll(pluginId: String) {
        store = store.copy(plugins = store.plugins - pluginId)
        session.clear(pluginId)
        persist()
    }

    /**
     * 清单新增但尚未批准的能力。权限页据此出"待重新审核"标记；是否为空即"要不要重审"。
     */
    fun addedCapabilities(pluginId: String, declared: List<String>): Set<String> =
        canonicalCapabilities(declared) - canonicalCapabilities(
            store.plugins[pluginId]?.declaredCapabilities.orEmpty(),
        )

    /** 当前生效的能力集合：既不含未声明的，也不含"升级新增未批准的"。 */
    fun grantedCapabilities(pluginId: String, declared: List<String>): Set<String> {
        seedFromDeclaration(pluginId, declared)
        return canonicalCapabilities(declared.filterNot { it == RUNTIME_PYTHON }).intersect(liveCapabilities(pluginId))
    }

    /**
     * Python 档**只能由用户显式授予**：走 [grant] 传 `capability = RUNTIME_PYTHON`，
     * 读用 [isApproved]。不提供会话档 —— 它等于整台应用的数据目录，"临时"没有意义。
     */

    fun recentAudit(limit: Int = 50): List<PluginCapabilityAudit.Event> = audit.recent(limit)

    /** 权限页一次性需要的账本视图：账本记录的声明集合 + 逐能力明细。 */
    fun permissionView(pluginId: String): PluginPermissionView {
        val record = store.plugins[pluginId] ?: return PluginPermissionView(emptyList(), emptyMap())
        return PluginPermissionView(record.declaredCapabilities, record.grants)
    }

    /**
     * 安装/启用时按**已重验的 manifest** 落账 —— 播种的唯一入口。
     * 已有账本时不动 grants：新增能力只会被记为待审，绝不自动放行。
     */
    fun seedFromDeclaration(pluginId: String, declared: List<String>): PluginPermissionRecord {
        store.plugins[pluginId]?.let { return it }
        val declarableCaps = declared.filterNot { it == RUNTIME_PYTHON }
        val seeded = PluginPermissionRecord(
            declaredCapabilities = canonicalCapabilities(declarableCaps).sorted(),
            declarationHash = declarationHash(declarableCaps),
            grants = declarableCaps.toSet().associateWith { capability ->
                // 首见即按声明批量放行，但标成 install-auto：升级扩权不能再走这条路。
                // 例外是 ui.interactive —— 提交表单会改数据，这一条要用户亲手打开：
                // 它仍然留在 declaredCapabilities 里（看得见），只是默认关。
                PluginCapabilityGrant(
                    allowed = capability != UI_INTERACTIVE &&
                capability != EVENT_ON_MESSAGE &&
                capability != EVENT_ON_TOOL_CALL &&
                capability != HOOK_TOOL_CALL_BEFORE &&
                capability != HOOK_SYSTEM_PROMPT &&
                capability != SCHEDULE &&
                capability != NETWORK_LOCAL &&
                capability != APP_NAVIGATE &&
                capability != APP_MESSAGES &&
                capability != APP_THEME &&
                capability != UI_SLOT &&
                capability != RUNTIME_LONG &&
                capability != SCHEDULE_BACKGROUND &&
                capability != RUNTIME_SERVICE,
                    decidedBy = PluginCapabilityGrant.DECIDED_BY_INSTALL_AUTO,
                    decidedAt = now(),
                )
            },
        )
        store = store.copy(plugins = store.plugins + (pluginId to seeded))
        persist()
        return seeded
    }

    private fun liveCapabilities(pluginId: String): Set<String> {
        val record = store.plugins[pluginId] ?: return emptySet()
        val always = canonicalCapabilities(record.grants.filterValues { it.allowed }.keys)
            .intersect(canonicalCapabilities(record.declaredCapabilities))
        return always + canonicalCapabilities(session.liveCapabilities(pluginId))
    }

    private fun actionsOf(capabilities: Set<String>): Set<String> =
        CAPABILITY_ACTIONS.filterKeys { it in capabilities }.values.flatten().toSet() + ALWAYS_ALLOWED_ACTIONS

    private fun deny(pluginId: String, action: String, capability: String, reason: String): Decision {
        audit.record(pluginId, action, capability, allowed = false)
        return Decision.Deny(capability, reason)
    }

    private fun writeGrant(
        pluginId: String,
        capability: String,
        allowed: Boolean,
        declared: List<String>,
        decidedBy: String,
    ) {
        store = store.copy(
            plugins = store.plugins.withRecord(pluginId, declared) { record ->
                record.copy(
                    grants = record.grants + (capability to PluginCapabilityGrant(allowed, decidedBy, now())),
                )
            },
        )
        persist()
    }

    private fun persist() {
        runCatching { PluginPermissionStore.toFile(storeFile, store) }
            .onFailure { Logger.w(TAG, "权限账本写入失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "PluginCapability"
        private const val UNSUPPORTED_ACTION =
            "未知桥接动作，不在能力总线的授权范围内（新增动作必须先在 CAPABILITY_ACTIONS 登记能力映射）"

        /** 联网能力名，与清单词汇一致（沙盒网络出口按它判定）。 */
        const val NETWORK = "network"

    /**
     * 允许访问**私网/本机**地址（局域网里的服务、127.0.0.1、`.local`）。
     *
     * 与 [NETWORK] 是两件事：前者是能不能联网，这条是能不能连内网。
     * 默认关：内网地址能把请求打到家里的路由器、NAS、本机的其它服务上，
     * 这属于要用户明确点头的事，而不是装个插件就默认拥有。
     */
    const val NETWORK_LOCAL = "network.local"

    /** 让宿主跳转到某个页面（页面白名单见 `PluginAppNavigation`）。默认关。 */
    const val APP_NAVIGATE = "app.navigate"

    /**
     * 往会话里写消息。默认关且敏感：写进去的内容用户会当成"助手说过的话"，
     * 这是能伪造历史的能力，必须由用户明确打开。
     */
    const val APP_MESSAGES = "app.messages"

    /** 切明暗模式（浅色/深色/跟随系统）。默认关。 */
    const val APP_THEME = "app.theme"

    /**
     * 往宿主界面槽位里放自己的界面片段。
     *
     * 默认关：这条能改变用户**看到的**界面，而且内容来自插件包，
     * 属于"要用户点头才显示"的东西。
     */
    const val UI_SLOT = "ui.slot"

    /**
     * 长任务：单次执行可以超过默认的 10 秒（上限 10 分钟）。
     *
     * 默认关：长时间占用意味着更久的电量与内存占用，而且插件卡住时用户会更晚发现。
     */
    const val RUNTIME_LONG = "runtime.long"

    /**
     * 后台唤醒：应用不在前台（甚至进程被回收）时，定时任务仍可被系统唤醒补跑。
     *
     * 与 [SCHEDULE] 分开是刻意的：`schedule` 管"能不能按时间跑"，
     * 这条管"没人看着的时候能不能跑"。后者更敏感（耗电、无人值守），默认关。
     */
    const val SCHEDULE_BACKGROUND = "schedule.background"

    /**
     * 前台服务：插件可以常驻运行（一条常驻通知承载所有服务型插件）。
     *
     * 默认关，且是**最重的一项**：它意味着应用会在用户不看着的时候一直活着。
     */
    const val RUNTIME_SERVICE = "runtime.service"

        /**
         * Python 运行时档：**清单不能自我授予**，只能由用户在权限面板显式打开。
         *
         * 为什么单独一档：外部包可以写 `runtime: "python"`，而安全门只校验入口扩展名
         * （`PluginSecurityGate.kt:319-322`）且仍要求 `trust == "sandboxed"`。
         * 但 Python 跑在内置解释器的**子进程**里、与应用**同 UID**，可直接读写应用数据目录
         * （`SkillMediaToolsImpl.kt` 的 python 分支注释自述）—— 称它 sandboxed 是假的。
         * 所以它不进播种、不进会话表、也不受清单 capabilities 影响。
         */
        const val RUNTIME_PYTHON = "runtime.python"

        /**
         * 交互界面能力（表单提交会**改插件自己的数据**），默认不放行。
         *
         * 与 [RUNTIME_PYTHON] 的区别：Python 那条连"声明"都不接受（只能单独授予），
         * 而这一条要留在 `declaredCapabilities` 里 —— 面板上看不见一个能力，
         * 用户就没法批准它；这里要的是"看得见、默认关、点开才生效"。
         */
        const val UI_INTERACTIVE = "ui.interactive"

    /**
     * 懒激活事件（观察型）：每条用户消息、每次工具调用都会叫醒一次插件。
     *
     * 默认**关闭**：声明 ≠ 授权。这两个事件本身不改数据，但会持续运行插件代码，
     * 属于"要用户点头才跑"的那一类；作者在清单里声明后，用户可在能力面板里打开。
     */
    const val EVENT_ON_MESSAGE = "event.onMessage"

    const val EVENT_ON_TOOL_CALL = "event.onToolCall"

    /**
     * 决策型钩子：工具调用前能否**拦下**。
     *
     * 默认关闭且属于敏感能力：它能阻止用户本来能做的事，
     * 也意味着插件能看到工具名与参数（参数里常有用户内容）。
     */
    const val HOOK_TOOL_CALL_BEFORE = "hook.toolCallBefore"

    /**
     * 决策型钩子：能否往系统提示词里追加文本。
     *
     * 默认关闭且属于敏感能力：系统提示词决定模型怎么理解自己的角色，
     * 能往里写文本就等于能改写助手行为。追加内容一律带来源标记（见 PluginPromptInjection）。
     */
    const val HOOK_SYSTEM_PROMPT = "hook.systemPrompt"

    /**
     * 定时执行：插件可以在应用存活期间按间隔跑自己的任务。
     *
     * 默认关闭且属于敏感能力：这是**无人值守**地运行插件代码，
     * 用户不在场时它照样会动数据。
     */
    const val SCHEDULE = "schedule"

        /** 通路自检动作：不携带任何外泄面，始终放行。 */
        val ALWAYS_ALLOWED_ACTIONS: Set<String> = setOf("echo")

        /** 共享空间动作族（`storage.shared:<名字>`）。 */
        val SHARED_STORAGE_ACTIONS: Set<String> = setOf(
            "storage_shared_get",
            "storage_shared_set",
            "storage_shared_delete",
            "storage_shared_list",
        )

        /**
         * 能力 → 桥动作。
         *
         * 新增桥动作必须同时登记在这里：不在表里的动作等于"谁都调不到"（fail-closed），
         * 而不是"不需要权限"。
         */
        val CAPABILITY_ACTIONS: Map<String, Set<String>> = mapOf(
            "resource.read" to setOf("fs_list", "fs_read"),
            "resource.write" to setOf("fs_write", "fs_delete"),
            NETWORK to setOf("http_get", "http_post"),
            "clipboard.read" to setOf("clipboard_read"),
            "clipboard.write" to setOf("clipboard_write"),
            "clipboard" to setOf("clipboard_read", "clipboard_write"),
            "notify" to setOf("notify"),
            "device.info" to setOf("device_info"),
            // 私有 KV 与 fs.* 是两种授权面：前者"能记状态"，后者"能读写目录里的文件"，
            // 合成一项的话权限清单就没有意义了。
            "storage" to setOf("storage_get", "storage_set", "storage_delete", "storage_list"),
            "config.write" to setOf("config_set"),
            "host.tool.invoke" to setOf("tool_invoke"),
            "chat.read" to setOf("chat_read"),
            "host.model.call" to setOf("model_call"),
            // app.*：插件能改应用里的东西。逐项授权、默认关 —— 这些动作直接改变用户看到/拥有的状态。
            "app.navigate" to setOf("app_navigate"),
            "app.messages" to setOf("app_insert_message"),
            "app.theme" to setOf("app_set_theme_mode"),
        )

        /**
         * 同义拼写归一化。
         *
         * 仓库里两套词汇并存：内置清单与 `tools/market-signer/sign.py` 用 `resource.*`，
         * 清单注释、author_plugin 参数说明、市场 README 用 `storage.*`。只认一套会让
         * 另一套的真实包被新闸门静默挡死。
         *
         * 注意这里是**归一化**而不是往映射里加同义条目：后者会让"动作 → 能力"反查出现两个答案，
         * 拒绝提示与授权判定就会对不上（这条是被测试抓出来的）。
         */
        val CAPABILITY_ALIASES: Map<String, String> = mapOf(
            "storage.read" to "resource.read",
            "storage.write" to "resource.write",
        )

        /** 把声明/查询里的能力名统一成映射表的规范名。 */
        fun canonicalCapabilities(capabilities: Collection<String>): Set<String> =
            capabilities.map { CAPABILITY_ALIASES[it] ?: it }.toSet()

        private val CAPABILITY_FOR_ACTION: Map<String, String> =
            CAPABILITY_ACTIONS.entries.flatMap { (capability, actions) -> actions.map { it to capability } }.toMap()

        /** 声明集合的指纹：排序去重后取摘要前缀，用于展示与"是否变过"的快速比较。 */
        fun declarationHash(capabilities: Collection<String>): String {
            val joined = capabilities.toSortedSet().joinToString("|")
            if (joined.isEmpty()) return "empty"
            val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** 用户操作时同步刷新"当前声明集合"，之后插件再扩权才算新增。 */
        private fun Map<String, PluginPermissionRecord>.withRecord(
            pluginId: String,
            declared: List<String>,
            transform: (PluginPermissionRecord) -> PluginPermissionRecord,
        ): Map<String, PluginPermissionRecord> {
            val existing = this[pluginId] ?: PluginPermissionRecord(
                declaredCapabilities = canonicalCapabilities(declared).sorted(),
                declarationHash = declarationHash(declared),
            )
            return this + (pluginId to transform(existing).copy(
                declaredCapabilities = canonicalCapabilities(declared).sorted(),
                declarationHash = declarationHash(declared),
            ))
        }
    }
}
