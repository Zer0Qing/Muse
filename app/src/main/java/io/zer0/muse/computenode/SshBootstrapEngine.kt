package io.zer0.muse.computenode

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import io.zer0.common.Logger
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * SSH 自举引擎。
 *
 * 负责把 APK assets 内的 bootstrap.sh 与 server.mjs 推到远端临时目录，执行安装脚本，
 * 并把脚本 stdout 上的 @@MUSE_BOOT 进度行解析成 [BootstrapEvent] 流。
 *
 * 凭据只在本类内部解密并交给 JSch；日志与异常消息只写主机/阶段等非敏感信息，
 * 密码、私钥与 Token 一律不进入日志。
 */
class SshBootstrapEngine(
    private val context: Context,
    private val cipher: SecureKeyCipher = SecureKeyStore,
) {

    /**
     * 连接节点并执行自举。
     *
     * 事件流按脚本输出顺序发射；遇到认证失败、上传失败或脚本非零退出时以
     * [SshBootstrapException] 结束。调用方取消收集会断开 SSH 会话。
     */
    fun bootstrap(
        node: RemoteComputeNodeEntity,
        options: BootstrapOptions = BootstrapOptions(),
    ): Flow<BootstrapEvent> = flow {
        emitAll(bootstrap(node.toSshConnectionSpec(cipher), options))
    }

    /**
     * 使用已解析的连接参数执行自举。凭据由 [SshConnectionSpec] 持有，
     * 其 toString 已脱敏，不得在日志里直接打印。
     */
    fun bootstrap(
        spec: SshConnectionSpec,
        options: BootstrapOptions = BootstrapOptions(),
    ): Flow<BootstrapEvent> = flow {
        val session = SshSessions.connect(spec)
        try {
            val stageDir = uploadBootstrapAssets(session)
            emitAll(runBootstrap(session, stageDir, options))
        } finally {
            session.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** 上传两份资产到 ~/.muse-node/stage，返回远端绝对路径。 */
    private fun uploadBootstrapAssets(session: Session): String {
        val channel = session.openChannel("sftp")
        val sftp = channel as? ChannelSftp
            ?: throw SshBootstrapException("SSH server did not open an SFTP channel")
        sftp.connect(SESSION_TIMEOUT_MS)
        try {
            val home = sftp.realpath(".")
            val museDir = "$home/.muse-node"
            val stageDir = "$museDir/stage"
            ensureDirectory(sftp, museDir)
            ensureDirectory(sftp, stageDir)
            putAsset(sftp, ASSET_BOOTSTRAP, "$stageDir/bootstrap.sh", MODE_0755)
            putAsset(sftp, ASSET_SERVER, "$stageDir/server.mjs", MODE_0700)
            return stageDir
        } finally {
            sftp.disconnect()
        }
    }

    private fun ensureDirectory(sftp: ChannelSftp, path: String) {
        try {
            if (sftp.stat(path).isDir) return
            throw SshBootstrapException("remote path is not a directory: $path")
        } catch (e: SftpException) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
            sftp.mkdir(path)
        }
    }

    private fun putAsset(sftp: ChannelSftp, assetPath: String, remotePath: String, mode: Int) {
        context.assets.open(assetPath).use { input ->
            sftp.put(input, remotePath)
        }
        sftp.chmod(mode, remotePath)
    }

    /** 执行 bootstrap.sh 并把每一行 @@MUSE_BOOT 转成事件。 */
    private fun runBootstrap(
        session: Session,
        stageDir: String,
        options: BootstrapOptions,
    ): Flow<BootstrapEvent> = flow {
        val channel = session.openChannel("exec")
        val exec = channel as? ChannelExec
            ?: throw SshBootstrapException("SSH server did not open an exec channel")
        val stderr = CappedOutputStream(MAX_STDERR_BYTES)
        exec.setErrStream(stderr)
        exec.setCommand(buildBootstrapCommand(stageDir, options))
        try {
            exec.connect(SESSION_TIMEOUT_MS)
            // 取消收集时主动断开通道，避免阻塞在 readLine() 上无法退出。
            val disconnectOnCompletion = currentCoroutineContext().job.invokeOnCompletion {
                exec.disconnect()
            }
            try {
                val reader = exec.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    val event = BootstrapEventParser.parseLine(line)
                    if (event != null) {
                        emit(event)
                    } else if (line.trimStart().startsWith(BootstrapEventParser.PREFIX)) {
                        Logger.w(TAG, "ignored malformed @@MUSE_BOOT line")
                    }
                }
            } finally {
                disconnectOnCompletion.dispose()
            }
            val exitCode = exec.exitStatus
            if (exitCode != 0) {
                val detail = stderr.text().trim().takeLast(MAX_ERROR_DETAIL_CHARS)
                throw SshBootstrapException("bootstrap exited with code $exitCode: $detail")
            }
        } finally {
            exec.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildBootstrapCommand(stageDir: String, options: BootstrapOptions): String = buildString {
        append("cd ").append(shellQuote(stageDir))
        append(" && sh ./bootstrap.sh")
        append(" --mode ").append(options.mode.wireValue)
        append(" --bind ").append(options.bindAddress)
        append(" --packs ").append(shellQuote(options.packs.joinToString(",")))
        append(" --create-swap ").append(options.createSwapMb)
        append(" --port ").append(options.port)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "SshBootstrapEngine"
        private const val ASSET_BOOTSTRAP = "compute-node/bootstrap.sh"
        private const val ASSET_SERVER = "compute-node/server.mjs"
        private const val SESSION_TIMEOUT_MS = 20_000
        private const val MAX_STDERR_BYTES = 32 * 1024
        private const val MAX_ERROR_DETAIL_CHARS = 600
        private const val MODE_0755 = 493
        private const val MODE_0700 = 448
    }
}

/** 自举模式，与 bootstrap.sh 的 --mode 取值一致。 */
enum class BootstrapMode(val wireValue: String) {
    PROBE("probe"),
    INSTALL("install"),
    PACK("pack"),
}

/** 一次自举的脚本参数。 */
data class BootstrapOptions(
    val mode: BootstrapMode = BootstrapMode.INSTALL,
    val bindAddress: String = "127.0.0.1",
    val packs: List<String> = listOf("core"),
    val createSwapMb: Int = 0,
    val port: Int = 18765,
) {
    init {
        require(bindAddress == "127.0.0.1" || bindAddress == "0.0.0.0") {
            "bindAddress must be 127.0.0.1 or 0.0.0.0"
        }
        require(port in 1..65535) { "port out of range" }
        require(createSwapMb >= 0) { "createSwapMb must be >= 0" }
        require(packs.isNotEmpty()) { "packs must not be empty" }
        require(packs.all { PACK_NAME.matches(it) }) { "invalid pack name" }
    }

    private companion object {
        private val PACK_NAME = Regex("[a-z0-9][a-z0-9-]*")
    }
}

/** SSH 认证材料。实现类重写 toString，避免日志或崩溃报告带出明文凭据。 */
sealed interface SshAuth {
    class Password(val value: String) : SshAuth {
        override fun toString(): String = "SshAuth.Password(***)"
    }

    class PrivateKey(val pem: String, val passphrase: String? = null) : SshAuth {
        override fun toString(): String = "SshAuth.PrivateKey(***)"
    }
}

/** 一条 SSH 连接的全部非敏感参数与认证材料。 */
data class SshConnectionSpec(
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshAuth,
    val connectTimeoutMs: Int = 15_000,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
    }
}

/**
 * 把节点实体转换成连接参数：encryptedCredential 经 [SecureKeyCipher.decryptOrNull] 解密，
 * 失败时显式报错，不把损坏的密文当成空密码继续连接。
 */
suspend fun RemoteComputeNodeEntity.toSshConnectionSpec(
    cipher: SecureKeyCipher = SecureKeyStore,
): SshConnectionSpec {
    if (host.isBlank() || username.isBlank()) {
        throw SshBootstrapException("node connection is incomplete")
    }
    val plain = cipher.decryptOrNull(encryptedCredential)
        ?: throw SshBootstrapException("stored SSH credential cannot be decrypted")
    if (plain.isBlank()) {
        throw SshBootstrapException("stored SSH credential is empty")
    }
    val auth = when (authType.uppercase(Locale.US)) {
        "PASSWORD" -> SshAuth.Password(plain)
        "PRIVATE_KEY" -> SshAuth.PrivateKey(plain)
        else -> throw SshBootstrapException("unsupported SSH auth type")
    }
    return SshConnectionSpec(
        host = host,
        port = sshPort,
        username = username,
        auth = auth,
    )
}

/** 自举过程中的一条结构化进度事件。 */
data class BootstrapEvent(
    val phase: BootstrapPhase,
    val step: String,
    val percent: Int,
    val status: BootstrapStatus,
    val message: String,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    /** data 可能含节点 Token；任何日志/异常打印都不能带上它。 */
    override fun toString(): String =
        "BootstrapEvent(phase=" + phase.wireValue + ", step=" + step +
            ", percent=" + percent + ", status=" + status.wireValue + ")"

    /** 读取 data 中的字符串字段；非字符串或缺失返回 null。 */
    fun stringValue(key: String): String? = (data[key] as? JsonPrimitive)?.contentOrNull
}

/** 自举阶段，对应脚本的 PROBE / DAEMON / PACKS / VERIFY。 */
enum class BootstrapPhase(val wireValue: String) {
    PROBE("PROBE"),
    DAEMON("DAEMON"),
    PACKS("PACKS"),
    VERIFY("VERIFY"),
    ;

    companion object {
        fun fromWire(value: String): BootstrapPhase? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 事件状态，对应脚本的 running / ok / warn / error。 */
enum class BootstrapStatus(val wireValue: String) {
    RUNNING("running"),
    OK("ok"),
    WARN("warn"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String): BootstrapStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 逐行解析器：只接受以 @@MUSE_BOOT + 空格开头的 JSON 行。 */
object BootstrapEventParser {
    const val PREFIX = "@@MUSE_BOOT "
    private const val TAG = "BootstrapEventParser"

    fun parseLine(line: String): BootstrapEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val payload = trimmed.removePrefix(PREFIX)
        return try {
            val obj = Json.parseToJsonElement(payload) as? JsonObject ?: return null
            val phase = obj["phase"]?.jsonPrimitive?.contentOrNull?.let(BootstrapPhase::fromWire) ?: return null
            val step = obj["step"]?.jsonPrimitive?.contentOrNull ?: return null
            val percent = obj["percent"]?.jsonPrimitive?.intOrNull ?: return null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull?.let(BootstrapStatus::fromWire) ?: return null
            val message = obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val data = when (val element = obj["data"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> element
                is JsonArray -> return null
                is JsonPrimitive -> return null
            }
            BootstrapEvent(
                phase = phase,
                step = step,
                percent = percent,
                status = status,
                message = message,
                data = data,
            )
        } catch (e: Exception) {
            // 非协议行与坏 JSON 都可能出现在同一条 stdout 上；跳过即可，日志不带原始内容。
            Logger.d(TAG, "skipped malformed bootstrap event: " + e.javaClass.simpleName)
            null
        }
    }
}

/** 自举失败；message 只包含阶段/退出码等非敏感信息。 */
class SshBootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 建立 JSch 会话的共用入口，自举与隧道管理器都从这里取连接。 */
internal object SshSessions {
    private const val IDENTITY_NAME = "muse-node-key"
    private const val SERVER_ALIVE_INTERVAL_MS = 30_000
    private const val SERVER_ALIVE_COUNT_MAX = 3

    fun connect(spec: SshConnectionSpec): Session {
        val jsch = JSch()
        val session = jsch.getSession(spec.username, spec.host, spec.port)
        try {
            // 首次连接没有可核对的主机指纹，交给用户确认节点地址后直接建立通道。
            session.setConfig("StrictHostKeyChecking", "no")
            when (val auth = spec.auth) {
                is SshAuth.Password -> {
                    session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    session.setPassword(auth.value)
                }
                is SshAuth.PrivateKey -> {
                    session.setConfig("PreferredAuthentications", "publickey")
                    jsch.addIdentity(
                        IDENTITY_NAME,
                        auth.pem.toByteArray(Charsets.UTF_8),
                        null,
                        auth.passphrase?.toByteArray(Charsets.UTF_8),
                    )
                }
            }
            session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
            session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
            session.connect(spec.connectTimeoutMs)
            return session
        } catch (e: JSchException) {
            session.disconnect()
            throw SshBootstrapException("SSH connection failed: " + e.javaClass.simpleName, e)
        }
    }
}

/** 有上限的 stderr 收集器：只保留前 N 字节，防止远端日志撑爆内存。 */
private class CappedOutputStream(private val limit: Int) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (buffer.size() < limit) buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val remaining = limit - buffer.size()
        if (remaining > 0) buffer.write(b, off, minOf(len, remaining))
    }

    fun text(): String = String(buffer.toByteArray(), Charsets.UTF_8)
}

/** 把底层网络异常翻译成可展示的短原因，不携带主机、账号或任何凭据。 */
internal fun sshFailureReason(e: Exception): String {
    val jsch = e as? JSchException ?: e.cause as? JSchException
    val message = jsch?.message?.lowercase()
    return when {
        message?.contains("auth") == true -> "authentication failed"
        message?.contains("timeout") == true -> "connection timed out"
        jsch != null -> "ssh connection failed"
        e is java.net.UnknownHostException -> "host not found"
        e is java.net.ConnectException -> "connection refused"
        else -> e.javaClass.simpleName
    }
}
