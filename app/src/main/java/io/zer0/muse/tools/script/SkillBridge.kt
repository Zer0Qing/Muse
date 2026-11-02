package io.zer0.muse.tools.script

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.zer0.common.AppJson
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolFailureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * F-17 + v2.2.1 大沙盒:JS 桥接执行器（__bridge__）。
 *
 * JS 函数可通过返回桥接请求对象获得受控能力（网络/沙盒文件/剪贴板/通知/设备信息）：
 * ```js
 * return { __bridge__: true, action: "http_get", params: { url: "..." } };
 * ```
 * 一次执行只截获一个桥接请求，Kotlin 侧在脚本执行结果处取到它，审计后执行对应的
 * 安全实现，再把结果作为技能返回值返回（request-response 模式）。
 *
 * 注意边界：桥接是「让脚本拿到受控能力」的通道（沙盒文件/剪贴板/通知等），
 * 不是网络代理——沙盒里直接 fetch 不走这里，因而不受本文件的 SSRF 判据保护。
 *
 * 动作清单与门槛（v2.x 沙盒放开后不再需要逐项声明）：
 *  - `echo`：原样回显（验证通路）
 *  - `http_get` / `http_post`：SSRF 防护 HTTP（拒内网/本地地址）
 *  - `fs_list` / `fs_read` / `fs_write` / `fs_delete`：沙盒目录内文件操作
 *    （限宿主的 [Host.fsRoot]，路径 canonical 校验防逃逸）
 *  - `clipboard_read` / `clipboard_write`
 *  - `notify`：应用内提示
 *  - `device_info`：设备基础信息
 *
 * 放行动作集**必须由调用方按能力账本给出**（见 `PluginCapabilityGateway.allowedActions`）；
 * 本对象不再自行决定"全放行"—— 能力声明过去只是展示品，现在它是唯一的门。
 * 调用方（skill 执行结果处理处）先调用 [tryHandle]：若返回值不含 `__bridge__`
 * 字段则返回 [HandleResult.NotBridge]（按普通结果处理）；否则返回执行产物/错误。
 */
object SkillBridge {

    private const val TAG = "SkillBridge"

    /** v2.2.1: http_post 请求体上限(256KB)。 */
    private const val MAX_REQUEST_BYTES = 256 * 1024

    /** 插件私有 KV 的文件名与配额：整份 8MB、单项 256KB、list 最多回 200 个键。 */
    private const val KV_FILE_NAME = ".muse-kv.json"
    private const val MAX_KV_TOTAL_BYTES = 8 * 1024 * 1024
    private const val MAX_KV_VALUE_BYTES = 256 * 1024
    private const val MAX_KV_LISTED_KEYS = 200
    private val KEY_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    /** B-2: 手动跟随重定向的最大跳数,防止重定向链无限延伸。 */
    private const val MAX_REDIRECTS = 5

    /** 默认放行的最小动作集（向后兼容：仅 echo + http_get）。 */
    val DEFAULT_ALLOWED_ACTIONS: Set<String> = setOf("echo", "http_get")

    /**
     * 全部桥接动作。**仅作全集定义**（校验与 UI 列举用），调用方不该再直接把它当放行集。
     *
     * 保留的底线不在“动作是否允许”，而在各动作实现内：
     *  - `fs_*` 锁定在宿主 [Host.fsRoot]（路径 canonical 校验防逃逸）；
     *  - `http_*` 走 SSRF 防护（拒内网/本地地址）。
     */
    val ALL_ACTIONS: Set<String> = setOf(
        "echo",
        "http_get",
        "http_post",
        "fs_list",
        "fs_read",
        "fs_write",
        "fs_delete",
        "clipboard_read",
        "clipboard_write",
        "notify",
        "device_info",
    )

    /**
     * v2.2.1: 桥接宿主 —— 提供动作所需的系统上下文与沙盒根目录。
     *
     * @param context 剪贴板/提示等系统能力需要；这类动作之外的场景可为 null
     * @param fsRoot `fs_*` 动作的沙盒根（如 `workspace/plugins/<pluginId>`）；为空时 fs 动作拒绝
     */
    class Host(val context: Context?, val fsRoot: File?)

    /**
     * 桥接专用 HTTP 出口。DNS 解析、连接地址校验和逐跳重定向都在该出口内完成。
     * 不复用其他网络出口，避免把 DNS 策略泛化到聊天、插件或 WebView 流量。
     */
    private val bridgeHttpClient: SkillBridgeHttpClient by lazy { SkillBridgeHttpClient() }

    /**
     * 允许访问私网/本机的客户端（`network.local` 能力授权后才用）。
     *
     * 判定交给客户端那条谓词，而不是在这里再写一份私网规则 ——
     * 两份规则一定会漂移，而漂移的表现是"某些地址偶尔能连"，最难查。
     */
    private val bridgeHttpClientLocal: SkillBridgeHttpClient by lazy {
        SkillBridgeHttpClient(addressAllowed = { true })
    }

    /**
     * 桥接处理结果密封类。
     *
     *  - [NotBridge]：返回值不含 `__bridge__`，调用方按普通脚本结果继续处理
     *  - [Output]：桥接请求已执行，产出结果 JSON 字符串
     *  - [Failure]：桥接请求解析或执行失败（含"action 不支持"）
     */
    sealed class HandleResult {
        object NotBridge : HandleResult()
        data class Output(val json: String) : HandleResult()
        data class Failure(val message: String) : HandleResult()
    }

    /**
     * 尝试处理一个 skill 脚本的执行返回值。
     *
     * @param valueJson 脚本返回值的 JSON 字符串（SkillEngineResult.Success.valueJson）
     * @param allowedActions 放行动作集，必须由调用方按**能力账本**给出（见 `PluginCapabilityGateway.allowedActions`）
     * @param host 桥接宿主（fs/剪贴板等动作需要）
     * @return 见 [HandleResult]
     */
    // 这个函数是桥接动作的总调度：每个 action 一个分支 + 一处授权判定，
    // 复杂度来自"动作表"本身而不是逻辑纠缠；拆开只会让"哪些动作走哪条路"更难读。
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun tryHandle(
        valueJson: String,
        allowedActions: Set<String> = DEFAULT_ALLOWED_ACTIONS,
        host: Host? = null,
        /** 是否允许访问私网/本机地址（来自 `network.local` 能力的授权结果）。 */
        allowLocalNetwork: Boolean = false,
    ): HandleResult {
        val obj = runCatching {
            AppJson.parseToJsonElement(valueJson) as? JsonObject
        }.getOrNull() ?: return HandleResult.NotBridge
        val isBridge = (obj["__bridge__"] as? JsonPrimitive)?.booleanOrNull == true
        if (!isBridge) return HandleResult.NotBridge

        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("__bridge__ 缺少 action 字段")
        if (action !in allowedActions) {
            return HandleResult.Failure(
                "__bridge__ action '$action' 未获授权：插件需在清单声明对应能力，并在 设置 → 工具与扩展 → 插件 → 权限 中批准",
            )
        }
        val params = obj["params"] as? JsonObject ?: buildJsonObject { }
        return when (action) {
            "echo" -> HandleResult.Output(params.toString())
            "http_get" -> withContext(Dispatchers.IO) { execHttpGet(params, host, allowLocalNetwork) }
            "http_post" -> withContext(Dispatchers.IO) { execHttpPost(params, host, allowLocalNetwork) }
            "fs_list" -> withContext(Dispatchers.IO) { execFsList(host, params) }
            "fs_read" -> withContext(Dispatchers.IO) { execFsRead(host, params) }
            "fs_write" -> withContext(Dispatchers.IO) { execFsWrite(host, params) }
            "fs_delete" -> withContext(Dispatchers.IO) { execFsDelete(host, params) }
            "clipboard_read" -> execClipboardRead(host)
            "clipboard_write" -> execClipboardWrite(host, params)
            "notify" -> execNotify(host, params)
            "storage_get" -> withContext(Dispatchers.IO) { execStorageGet(host, params) }
            "storage_set" -> withContext(Dispatchers.IO) { execStorageSet(host, params) }
            "storage_delete" -> withContext(Dispatchers.IO) { execStorageDelete(host, params) }
            "storage_list" -> withContext(Dispatchers.IO) { execStorageList(host) }
            "device_info" -> HandleResult.Output(deviceInfoJson())
            else -> HandleResult.Failure("__bridge__ action '$action' 不受支持")
        }
    }

    // ── 插件私有 KV（storage_*）───────────────────────────────────────────
    //
    // 为什么单独做一套而不是让插件自己 `fs_write` 一个 JSON：
    //  1. 配额要能算清楚 —— KV 走固定文件，超限能给出可行动的提示；散落的 fs_write 文件无从统计；
    //  2. `fs.*` 与 `storage` 是两种不同的授权面（前者是"能读写我目录里的文件"，
    //     后者是"能记住一点状态"），合并会让权限清单失去意义。

    /** 读一个键。不存在返回 `{value: null}` —— 不是错误，插件要能区分"没存过"和"存了 null"。 */
    private fun execStorageGet(host: Host?, params: JsonObject): HandleResult {
        val store = kvFile(host) ?: return kvRootMissing()
        val key = storageKey(params) ?: return HandleResult.Failure("storage_get 缺少合法的 key 参数")
        val entry = readKv(store)[key]
        val output = buildJsonObject {
            put("key", JsonPrimitive(key))
            put("found", JsonPrimitive(entry != null))
            if (entry != null) put("value", entry["value"] ?: JsonNull)
        }
        return HandleResult.Output(output.toString())
    }

    /** 写一个键：先收参数，再交给 [writeKv] 做体积与配额判定。 */
    private fun execStorageSet(host: Host?, params: JsonObject): HandleResult {
        val store = kvFile(host)
        val key = storageKey(params)
        val value = params["value"]
        return when {
            store == null -> kvRootMissing()
            key == null || value == null ->
                HandleResult.Failure("storage_set 需要合法的 key（字母数字开头，可含 . _ -，<=64）与 value 参数")
            else -> writeKv(store, key, value)
        }
    }

    /** 落盘一次 KV 写入：单项超限与整份配额都在这一处判，避免两套上限各写一遍。 */
    private fun writeKv(store: File, key: String, value: JsonElement): HandleResult {
        val itemBytes = value.toString().toByteArray(Charsets.UTF_8).size
        if (itemBytes > MAX_KV_VALUE_BYTES) {
            return HandleResult.Failure(
                "storage_set 单项 ${itemBytes / 1024}KB 超过上限(${MAX_KV_VALUE_BYTES / 1024}KB)；" +
                    "大块内容请改用 fs_write 落成文件，KV 只存索引",
            )
        }
        val snapshot = LinkedHashMap(readKv(store))
        snapshot[key] = buildJsonObject { put("value", value) }
        val serialized = encodeKv(snapshot)
        val totalBytes = serialized.toByteArray(Charsets.UTF_8).size
        if (totalBytes > MAX_KV_TOTAL_BYTES) {
            return HandleResult.Failure(
                "插件存储将达到配额上限(${MAX_KV_TOTAL_BYTES / 1024}KB)：" +
                    "先用 storage_delete 清理不再需要的键，或把大块内容改用 fs_write 落成文件",
            )
        }
        return runCatching { AtomicFileStore.writeText(store, serialized) }
            .fold(
                onSuccess = {
                    HandleResult.Output(
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("key", JsonPrimitive(key))
                            put("bytes", JsonPrimitive(totalBytes))
                        }.toString(),
                    )
                },
                onFailure = { HandleResult.Failure("storage_set 写入失败: ${it.message}") },
            )
    }

    private fun execStorageDelete(host: Host?, params: JsonObject): HandleResult {
        val store = kvFile(host) ?: return kvRootMissing()
        val key = storageKey(params) ?: return HandleResult.Failure("storage_delete 缺少合法的 key 参数")
        val snapshot = LinkedHashMap(readKv(store))
        val removed = snapshot.remove(key) != null
        if (removed) {
            runCatching { AtomicFileStore.writeText(store, encodeKv(snapshot)) }
                .onFailure { return HandleResult.Failure("storage_delete 写入失败: ${it.message}") }
        }
        return HandleResult.Output(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("removed", JsonPrimitive(removed))
            }.toString(),
        )
    }

    /** 只回键名与占用，不回值：避免一次 list 把整份数据塞进上下文。 */
    private fun execStorageList(host: Host?): HandleResult {
        val store = kvFile(host) ?: return kvRootMissing()
        val entries = readKv(store).entries
        return HandleResult.Output(
            buildJsonObject {
                put("limitBytes", JsonPrimitive(MAX_KV_TOTAL_BYTES))
                put("usedBytes", JsonPrimitive(encodeKv(readKv(store)).toByteArray(Charsets.UTF_8).size))
                put(
                    "keys",
                    kotlinx.serialization.json.buildJsonArray {
                        entries.take(MAX_KV_LISTED_KEYS).forEach { (key, value) ->
                            add(
                                buildJsonObject {
                                    put("key", JsonPrimitive(key))
                                    put("bytes", JsonPrimitive(value.toString().toByteArray(Charsets.UTF_8).size))
                                },
                            )
                        }
                    },
                )
            }.toString(),
        )
    }

    private fun kvRootMissing(): HandleResult = HandleResult.Failure("storage_* 需要插件沙盒目录，当前宿主未提供")

    private fun kvFile(host: Host?): File? {
        val root = host?.fsRoot ?: return null
        return File(root, KV_FILE_NAME)
    }

    private fun storageKey(params: JsonObject): String? {
        val key = (params["key"] as? JsonPrimitive)?.contentOrNull ?: return null
        return key.takeIf { KEY_NAME_REGEX.matches(it) }
    }

    private fun readKv(store: File): Map<String, JsonObject> {
        if (!store.isFile) return emptyMap()
        val obj = runCatching { AppJson.parseToJsonElement(store.readText()) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.entries.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { key to it }
        }.toMap()
    }

    private fun encodeKv(snapshot: Map<String, JsonObject>): String {
        val builder = LinkedHashMap<String, JsonObject>(snapshot)
        return AppJson.encodeToString(MapSerializer(String.serializer(), JsonObject.serializer()), builder)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    /** 执行 http_get：SSRF 防护(含重定向逐跳校验) + 完整输出落盘。 */
    private fun execHttpGet(params: JsonObject, host: Host?, allowLocal: Boolean = false): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_get 缺少 url 参数")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_get url 仅支持 http/https 协议")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_get 缺少完整响应存储上下文")
        return execHttpGetWithRedirects(url, outputDirectory, allowLocal)
    }

    /** v2.2.1: 执行 http_post（不跟随重定向，fail-closed）。 */
    // 参数校验分多道（url / 上下文 / 体积 / content-type），每道都要给作者一句可读的原因，
    // 合并成一条 when 会让"哪一步失败"变得难查 —— 这里的多个 return 是有意的。
    @Suppress("ReturnCount")
    private fun execHttpPost(params: JsonObject, host: Host?, allowLocal: Boolean = false): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_post 缺少 url 参数")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_post url 仅支持 http/https 协议")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_post 缺少完整响应存储上下文")
        val body = (params["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return HandleResult.Failure("http_post 请求体超过上限(${MAX_REQUEST_BYTES / 1024}KB)")
        }
        val contentType = (params["content_type"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "text/plain; charset=utf-8"
        return try {
            val client = if (allowLocal) bridgeHttpClientLocal else bridgeHttpClient
            val result = client.postComplete(url, body, contentType, outputDirectory)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_post 桥接失败: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_post 拒绝访问内网/非公网地址: ${e.url}"
            } else {
                // v2.2.1: 带地址与失败阶段(超时/解析/TLS)
                ToolFailureText.httpFailure("http_post", url, e)
            }
            HandleResult.Failure(message)
        }
    }

    /**
     * 手动跟随重定向的 http_get 执行。
     *
     * DNS 解析与连接级校验由 [SkillBridgeHttpClient] 完成；它为每一跳固定同一份已校验
     * 地址列表，避免“先校验 getAllByName、再由系统重新解析”的 TOCTOU。重定向仍由客户端
     * 禁用自动跟随并逐跳校验，任一跳失败即 fail-closed。
     */
    @Suppress("TooGenericExceptionCaught") // 底层网络异常统一转为用户可见失败信息
    private fun execHttpGetWithRedirects(
        startUrl: String,
        outputDirectory: File,
        allowLocal: Boolean = false,
    ): HandleResult {
        return try {
            val client = if (allowLocal) bridgeHttpClientLocal else bridgeHttpClient
            val result = client.getComplete(startUrl, outputDirectory, MAX_REDIRECTS)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_get 桥接失败: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_get 拒绝访问内网/非公网地址: ${e.url}"
            } else {
                // v2.2.1: 带地址与失败阶段(超时/解析/TLS)
                ToolFailureText.httpFailure("http_get", startUrl, e)
            }
            HandleResult.Failure(message)
        }
    }

    // ── 沙盒文件 ──────────────────────────────────────────────────────────

    private fun fsRootOf(host: Host?): File = host?.fsRoot ?: throw SkillBridgeFs.BridgeFsException("当前动作未提供沙盒目录")

    private fun execFsList(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            val entries = SkillBridgeFs.list(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path.ifEmpty { "." }))
                    put(
                        "entries",
                        buildJsonArray {
                            entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(entry.name))
                                        put("dir", JsonPrimitive(entry.dir))
                                        put("size", JsonPrimitive(entry.size))
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_list", e)
        }
    }

    private fun execFsRead(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_read 缺少 path 参数")
            val content = SkillBridgeFs.read(
                root = root,
                path = path,
                offsetChars = paramString(params, "offset_chars").toIntOrNull(),
                lengthChars = paramString(params, "length_chars").toIntOrNull(),
            )
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("content", JsonPrimitive(content))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_read", e)
        }
    }

    private fun execFsWrite(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_write 缺少 path 参数")
            val content = paramString(params, "content")
            val written = SkillBridgeFs.write(root, path, content)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("written_bytes", JsonPrimitive(written))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_write", e)
        }
    }

    private fun execFsDelete(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_delete 缺少 path 参数")
            SkillBridgeFs.delete(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("deleted", JsonPrimitive(true))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_delete", e)
        }
    }

    private fun fsFailure(action: String, e: Exception): HandleResult = HandleResult.Failure(
        if (e is SkillBridgeFs.BridgeFsException) {
            "$action 被拒绝: ${e.message}"
        } else {
            "$action 失败: ${e.message ?: "未知错误"}"
        },
    )

    // ── 系统能力 ──────────────────────────────────────────────────────────

    private suspend fun execClipboardRead(host: Host?): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("桥接宿主缺少 Context,无法读取剪贴板")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("剪贴板服务不可用")
                val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                HandleResult.Output(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_read 失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun execClipboardWrite(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("桥接宿主缺少 Context,无法写入剪贴板")
        val text = paramString(params, "text")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("剪贴板服务不可用")
                manager.setPrimaryClip(ClipData.newPlainText("muse-skill", text))
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_write 失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private suspend fun execNotify(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("桥接宿主缺少 Context,无法发送提示")
        val title = paramString(params, "title")
        val body = paramString(params, "body").ifBlank { title }
        if (body.isBlank()) return HandleResult.Failure("notify 缺少 title/body")
        return withContext(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(context, body, android.widget.Toast.LENGTH_SHORT).show()
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("notify 失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun deviceInfoJson(): String = buildJsonObject {
        put("brand", JsonPrimitive(Build.BRAND))
        put("model", JsonPrimitive(Build.MODEL))
        put("android", JsonPrimitive(Build.VERSION.RELEASE))
        put("sdk", JsonPrimitive(Build.VERSION.SDK_INT))
    }.toString()

    private fun paramString(params: JsonObject, key: String): String = (params[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
