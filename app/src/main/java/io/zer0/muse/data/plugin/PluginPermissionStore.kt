package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 插件能力授权账本（v2 重构 P0-1）。
 *
 * 核心约定：**清单里的 capabilities 只是"请求"，真正的授权是一条条记账的**。
 * 声明和授权混成一谈的话，用户看到的权限清单就和实际能做的事脱钩了 —— 那比没有权限系统更糟。
 * 之前我们只有"装/不装"两档，且声明与实际权限无关（见 [PluginCapabilityGateway] 注释）。
 *
 * 三档授权（[PluginPermissionTier]）：
 *  - [PluginPermissionTier.ALWAYS] 持久化到本文件；
 *  - [PluginPermissionTier.SESSION] / [PluginPermissionTier.ONCE] **只存在内存会话表**，
 *    进程重启即失效 —— 临时授权一旦落盘，就会变成"当时说临时、事后成永久"。
 *
 * 文件：`filesDir/plugin_permissions.json`，经 [AtomicFileStore] 原子写，避免进程被杀留下半个 JSON。
 */
@Serializable
data class PluginPermissionStore(
    val version: Int = CURRENT_VERSION,
    /** pluginId → 该插件的授权记录（只含 ALWAYS 档）。 */
    val plugins: Map<String, PluginPermissionRecord> = emptyMap(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        const val FILE_NAME = "plugin_permissions.json"

        fun fromFile(file: File): PluginPermissionStore = if (!file.exists()) {
            PluginPermissionStore()
        } else {
            // 坏文件按空账本处理：宁可回到"未授权"，也不要拿半个 JSON 当授权用。
            runCatching { AppJson.decodeFromString<PluginPermissionStore>(file.readText()) }
                .getOrElse { PluginPermissionStore() }
        }

        fun toFile(file: File, store: PluginPermissionStore) {
            AtomicFileStore.writeText(file, AppJson.encodeToString(serializer(), store))
        }
    }
}

/**
 * 一个插件的授权记录。
 *
 * @property declaredCapabilities 这批授权**当时**清单声明的能力集合；插件升级后用它算出"新增了哪些能力"。
 * @property declarationHash [declaredCapabilities] 的哈希；只作展示与快速比较，判定新增以集合差为准。
 */
@Serializable
data class PluginPermissionRecord(
    val declaredCapabilities: List<String> = emptyList(),
    val declarationHash: String = "",
    val grants: Map<String, PluginCapabilityGrant> = emptyMap(),
)

@Serializable
data class PluginCapabilityGrant(
    val allowed: Boolean,
    /** 谁批的：user / install-auto / agent。用户要知道这条权限是自己点的还是升级时自动落的。 */
    val decidedBy: String = DECIDED_BY_USER,
    val decidedAt: Long = 0L,
) {
    companion object {
        const val DECIDED_BY_USER = "user"
        const val DECIDED_BY_INSTALL_AUTO = "install-auto"
    }
}

/**
 * 权限页一次性读取的账本视图。
 *
 * @property declaredCapabilities 账本记录的"上次批准时"的能力声明集合（与当前清单对比即知是否扩权）
 */
data class PluginPermissionView(
    val declaredCapabilities: List<String>,
    val grants: Map<String, PluginCapabilityGrant>,
)

/** 授权档位。持久化只写 [ALWAYS]，另两档在内存会话表里。 */
enum class PluginPermissionTier {
    /** 只放行一次，用完即失效。 */
    ONCE,

    /** 本次进程内一直放行，重启失效。 */
    SESSION,

    /** 永久放行，落盘。 */
    ALWAYS,
}
