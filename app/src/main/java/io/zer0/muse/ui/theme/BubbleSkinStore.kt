package io.zer0.muse.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.museSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * 气泡皮肤目录 — 纯逻辑(无 Android 依赖),负责皮肤的解析/过滤/选择语义。
 *
 * 与 [BubbleSkinStore] 拆分的原因:存储层需要 DataStore/Context,
 * 而"哪些皮肤可被应用、选中不存在的皮肤怎么办"这一策略必须能在纯 JVM 单测中验证。
 *
 * 不变量:
 *  - 内置 default 永远由 [BubbleSkinResolver.defaultSkin] 提供,不进入自定义列表;
 *  - 校验不通过(含对比度不达标)的皮肤一律被过滤,消费侧因此回退内置 default;
 *  - 未选中/选中 id 不存在/选中内置 id 时返回 null,调用方传 null 即保持既有渲染。
 */
object BubbleSkinCatalog {

    /** 内置默认皮肤 id,与 [BubbleSkinResolver.defaultSkin] 的 id 保持一致。 */
    const val DEFAULT_SKIN_ID = "builtin-default"

    /** 自定义皮肤数量上限,防止 DataStore 单键无限增长。 */
    const val MAX_CUSTOM_SKINS = 32

    /** 解析自定义皮肤列表 JSON;任何解析失败都返回空列表而不是抛出。 */
    fun decode(raw: String?): List<BubbleSkin> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching {
            AppJson.decodeFromString(ListSerializer(BubbleSkin.serializer()), raw)
        }.getOrNull() ?: return emptyList()
        return sanitize(decoded)
    }

    fun encode(skins: List<BubbleSkin>): String =
        AppJson.encodeToString(ListSerializer(BubbleSkin.serializer()), sanitize(skins))

    /** 过滤非法皮肤/内置 id/重复 id,并限制数量;保证列表内容始终可安全渲染。 */
    fun sanitize(skins: List<BubbleSkin>): List<BubbleSkin> = skins
        .filter { it.id != DEFAULT_SKIN_ID && BubbleSkinValidator.isValid(it) }
        .distinctBy { it.id }
        .take(MAX_CUSTOM_SKINS)

    /** 新增或覆盖同 id 皮肤;非法皮肤不会进入列表(调用方保持现状 → 回退 default)。 */
    fun upsert(current: List<BubbleSkin>, skin: BubbleSkin): List<BubbleSkin> {
        if (skin.id == DEFAULT_SKIN_ID || !BubbleSkinValidator.isValid(skin)) return sanitize(current)
        return sanitize(current.filterNot { it.id == skin.id } + skin)
    }

    fun remove(current: List<BubbleSkin>, id: String): List<BubbleSkin> =
        sanitize(current.filterNot { it.id == id })

    /**
     * 解析导入的单张皮肤 JSON(分享文本/SAF 文件)。
     *
     * 非法 schema、非法数值范围或对比度不达标都返回 null,由 UI 提示失败并保持当前皮肤。
     */
    fun parseSkin(raw: String?): BubbleSkin? {
        if (raw.isNullOrBlank()) return null
        val skin = runCatching {
            AppJson.decodeFromString(BubbleSkin.serializer(), raw)
        }.getOrNull() ?: return null
        if (skin.id == DEFAULT_SKIN_ID || !BubbleSkinValidator.isValid(skin)) return null
        return skin
    }

    /**
     * 选择当前生效皮肤。
     *
     * @return 可渲染的自定义皮肤;内置 id / 空 id / 未找到 / 校验不通过一律返回 null,
     * 调用方把 null 传给气泡组件即保持既有默认外观。
     */
    fun select(skins: List<BubbleSkin>, selectedId: String): BubbleSkin? {
        if (selectedId == DEFAULT_SKIN_ID || selectedId.isBlank()) return null
        return sanitize(skins).firstOrNull { it.id == selectedId }
    }

    /**
     * 合并自定义皮肤与插件皮肤后的选择。
     *
     * 自定义皮肤保持既有优先级:同 id 时用户导入的皮肤获胜,安装/卸载插件不会改变用户
     * 已有选择的外观;仅当自定义列表未命中时才回退到插件皮肤。插件皮肤已由插件层校验,
     * 这里再校验一次,保证非法皮肤永远进不了渲染路径。
     *
     * @return 可渲染皮肤;内置 id / 空 id / 两处都未找到 / 校验不通过一律返回 null。
     */
    fun select(
        customSkins: List<BubbleSkin>,
        pluginSkins: List<BubbleSkin>,
        selectedId: String,
    ): BubbleSkin? {
        if (selectedId == DEFAULT_SKIN_ID || selectedId.isBlank()) return null
        select(customSkins, selectedId)?.let { return it }
        return pluginSkins.firstOrNull { it.id == selectedId && BubbleSkinValidator.isValid(it) }
    }
}

/**
 * 气泡皮肤轻量存储(DataStore Preferences,与 [io.zer0.muse.data.AppearanceSettingsStore]
 * 共用 `muse_settings` 文件)。
 *
 * 只保存两个键:当前选中皮肤 id + 自定义皮肤 JSON 列表。未写入过时
 * [selectedSkinIdFlow] 默认内置 default、[customSkinsFlow] 默认空列表,
 * [selectedSkinFlow] 因此为 null,ChatScreen/群聊页保持改造前的渲染路径。
 *
 * Phase 4 起可注入 [PluginSkinSource]:选中 id 命中已安装 ui-skin 插件的皮肤时返回该皮肤;
 * 插件被禁用/卸载/信任撤销或皮肤非法时,插件层不再返回该条目,这里自然回退内置 default。
 */
class BubbleSkinStore(
    private val context: Context,
    /** 插件皮肤来源;null 表示不消费插件皮肤(既有单测与未接入插件的调用方行为不变)。 */
    private val pluginSkinSource: PluginSkinSource? = null,
) {

    private val store get() = context.museSettingsDataStore

    val customSkinsFlow: Flow<List<BubbleSkin>> = store.data.map { prefs ->
        BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
    }

    val selectedSkinIdFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_SELECTED_SKIN_ID] ?: BubbleSkinCatalog.DEFAULT_SKIN_ID
    }

    /** 插件注册表变更信号;无插件来源时恒定,不改变既有 combine 语义。 */
    private val pluginRevisionFlow: Flow<Int> = pluginSkinSource?.revisionFlow ?: flowOf(0)

    /**
     * 已安装插件皮肤(含不可用条目,供设置页展示)。
     *
     * 读取会重新校验插件包并读磁盘,统一切到 IO 线程,避免在主线程做签名/摘要计算。
     */
    val pluginSkinsFlow: Flow<List<InstalledSkin>> = pluginRevisionFlow
        .map { pluginSkinSource?.listInstalledSkins(includeDisabled = true).orEmpty() }
        .flowOn(Dispatchers.IO)

    /** 当前生效皮肤;null = 使用内置 default(与改造前行为完全一致)。 */
    val selectedSkinFlow: Flow<BubbleSkin?> = combine(
        customSkinsFlow,
        selectedSkinIdFlow,
        pluginRevisionFlow,
    ) { skins, id, _ ->
        BubbleSkinCatalog.select(
            customSkins = skins,
            pluginSkins = pluginSkinSource?.listInstalledSkins().orEmpty().map { it.skin },
            selectedId = id,
        )
    }.flowOn(Dispatchers.IO)

    suspend fun saveSelectedSkinId(id: String) {
        store.edit { prefs -> prefs[KEY_SELECTED_SKIN_ID] = id }
    }

    /** 导入/更新一张皮肤;返回 false 表示皮肤非法被拒(已有列表不受影响)。 */
    suspend fun upsertSkin(skin: BubbleSkin): Boolean {
        if (!BubbleSkinValidator.isValid(skin) || skin.id == BubbleSkinCatalog.DEFAULT_SKIN_ID) {
            Logger.w("BubbleSkinStore", "拒绝保存非法气泡皮肤: ${skin.id}")
            return false
        }
        store.edit { prefs ->
            val current = BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
            prefs[KEY_CUSTOM_SKINS] = BubbleSkinCatalog.encode(BubbleSkinCatalog.upsert(current, skin))
        }
        return true
    }

    /** 删除皮肤;若删除的是当前选中项,选择回退到内置 default。 */
    suspend fun deleteSkin(id: String) {
        store.edit { prefs ->
            val current = BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
            prefs[KEY_CUSTOM_SKINS] = BubbleSkinCatalog.encode(BubbleSkinCatalog.remove(current, id))
            if (prefs[KEY_SELECTED_SKIN_ID] == id) prefs.remove(KEY_SELECTED_SKIN_ID)
        }
    }

    /** 解析并保存导入的皮肤 JSON;null 表示内容非法,由调用方提示失败。 */
    suspend fun importSkin(raw: String?): BubbleSkin? {
        val skin = BubbleSkinCatalog.parseSkin(raw) ?: return null
        return skin.takeIf { upsertSkin(it) }
    }

    private companion object {
        val KEY_SELECTED_SKIN_ID = stringPreferencesKey("bubble_skin_selected_id")
        val KEY_CUSTOM_SKINS = stringPreferencesKey("bubble_skin_custom_json")
    }
}
