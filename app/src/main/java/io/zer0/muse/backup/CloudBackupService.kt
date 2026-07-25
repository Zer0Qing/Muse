package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.backup.s3.S3Client
import io.zer0.muse.backup.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.9: 云备份服务(统一 S3 / WebDAV 接口)。
 *
 * 根据 [CloudBackupConfig.type] 派发到 [S3Client] 或 [WebDavClient],
 * 提供 uploadBackup / downloadBackup / listBackups 三个核心操作。
 *
 * 备份文件命名: muse-backup-yyyyMMdd-HHmmss.json
 *
 * v1.132 扩展:新增 [listBackups] / [downloadBackup] / [deleteBackup] / [testConnection],
 * 供 CloudBackupPage 展示远端备份列表、按版本恢复、删除旧版本、测试连接。
 *
 * @param client OkHttpClient(复用 named("chat"))
 */
class CloudBackupService(private val client: OkHttpClient) {

    companion object {
        /** M-004: 单次云端网络操作超时(毫秒)。 */
        private const val NETWORK_TIMEOUT_MS = 30_000L
        /** latest 备份文件名(downloadLatestBackup / hasBackup / uploadBackupWithLatest 复用)。 */
        private const val LATEST_FILE_NAME = "muse-backup-latest.json"
        /** v1.132: 备份文件名前缀(用于 listBackups 过滤归档版本)。 */
        private const val BACKUP_FILE_PREFIX = "muse-backup-"
        /** v1.132: listBackups 单次最多返回条数(防止远端目录爆炸)。 */
        private const val LIST_MAX_ITEMS = 100
    }

    /** 生成带时间戳的备份文件名。 */
    private fun backupFileName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "muse-backup-${fmt.format(Date())}.json"
    }

    /**
     * L-001: 统一的云端客户端抽象,屏蔽 S3 / WebDAV 的接口差异。
     * 通过 [createClient] 按 [CloudBackupConfig.type] 构造,避免在 upload/download/exists
     * 四个方法里重复 when 分发块。path 统一由 [resolveRemotePath] 解析。
     */
    private sealed interface CloudClient {
        fun upload(path: String, data: ByteArray): Boolean
        fun download(path: String): ByteArray?
        fun exists(path: String): Boolean
    }

    private class S3CloudClient(
        private val s3: S3Client,
        private val bucket: String,
    ) : CloudClient {
        override fun upload(path: String, data: ByteArray): Boolean = s3.putObject(bucket, path, data)
        override fun download(path: String): ByteArray? = s3.getObject(bucket, path)
        override fun exists(path: String): Boolean = s3.headObject(bucket, path)
    }

    private class WebDavCloudClient(
        private val dav: WebDavClient,
    ) : CloudClient {
        override fun upload(path: String, data: ByteArray): Boolean = dav.putFile(path, data)
        override fun download(path: String): ByteArray? = dav.getFile(path)
        override fun exists(path: String): Boolean = dav.exists(path)
    }

    /** L-001: 按 config.type 构造对应云端客户端;未配置/未知类型返回 null。 */
    private fun createClient(config: CloudBackupConfig): CloudClient? = when (config.type) {
        "s3" -> S3CloudClient(
            S3Client(config.s3Endpoint, config.s3Region, config.s3AccessKey, config.s3SecretKey, client),
            config.s3Bucket,
        )
        "webdav" -> WebDavCloudClient(
            WebDavClient(config.webdavUrl, config.webdavUsername, config.webdavPassword, client),
        )
        else -> null
    }

    /**
     * v1.132: 按 config.type 构造协程化客户端(直接调用 S3Client/WebDavClient 的 suspend API)。
     * 用于 [listBackups] / [downloadBackup] / [deleteBackup] / [testConnection]。
     */
    private sealed interface AsyncCloudClient {
        suspend fun list(prefix: String): List<RemoteFile>
        suspend fun download(path: String, localFile: File): Boolean
        suspend fun downloadBytes(path: String): ByteArray?
        suspend fun delete(path: String): Boolean
        suspend fun test(): Boolean
    }

    private class AsyncS3Client(
        private val s3: S3Client,
        private val bucket: String,
    ) : AsyncCloudClient {
        override suspend fun list(prefix: String): List<RemoteFile> = s3.list(bucket, prefix)

        override suspend fun download(path: String, localFile: File): Boolean =
            s3.download(bucket, path, localFile)

        override suspend fun downloadBytes(path: String): ByteArray? = s3.getObject(bucket, path)

        override suspend fun delete(path: String): Boolean = s3.delete(bucket, path)

        override suspend fun test(): Boolean = s3.testConnection(bucket)
    }

    private class AsyncWebDavClient(
        private val dav: WebDavClient,
    ) : AsyncCloudClient {
        override suspend fun list(prefix: String): List<RemoteFile> = dav.list(prefix)

        override suspend fun download(path: String, localFile: File): Boolean = dav.download(path, localFile)

        override suspend fun downloadBytes(path: String): ByteArray? = dav.getFile(path)

        override suspend fun delete(path: String): Boolean = dav.delete(path)

        override suspend fun test(): Boolean = dav.testConnection()
    }

    /** v1.132: 按 config.type 构造协程化客户端;未配置/未知类型返回 null。 */
    private fun createAsyncClient(config: CloudBackupConfig): AsyncCloudClient? = when (config.type) {
        "s3" -> AsyncS3Client(
            S3Client(config.s3Endpoint, config.s3Region, config.s3AccessKey, config.s3SecretKey, client),
            config.s3Bucket,
        )
        "webdav" -> AsyncWebDavClient(
            WebDavClient(config.webdavUrl, config.webdavUsername, config.webdavPassword, client),
        )
        else -> null
    }

    /** L-001: 按 config.type 拼接远程路径(s3 用 s3KeyPrefix,webdav 用 webdavPath)。 */
    private fun resolveRemotePath(config: CloudBackupConfig, fileName: String): String = when (config.type) {
        "s3" -> config.s3KeyPrefix.trimEnd('/') + "/" + fileName
        "webdav" -> config.webdavPath.trimEnd('/') + "/" + fileName
        else -> fileName
    }

    /**
     * 上传备份到云端。
     * @param config 云备份配置
     * @param data 备份 JSON 字节
     * @return true 成功;false 失败或未配置
     */
    suspend fun uploadBackup(config: CloudBackupConfig, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            Logger.w("CloudBackupService", "Cloud backup not configured")
            return@withContext false
        }
        val cc = createClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, backupFileName())
        // M-004: 云端网络操作加 30s 超时,避免弱网下协程长时间挂起
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.upload(path, data) } ?: false
    }

    /**
     * 下载最新备份(按文件名排序取最大)。
     * @param config 云备份配置
     * @return 备份 JSON 字节;null 失败或无备份
     */
    suspend fun downloadLatestBackup(config: CloudBackupConfig): ByteArray? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null
        // 简化实现:下载预设文件名 muse-backup-latest.json
        // 完整实现需要 PROPFIND/listObjects 列目录取最新,留待扩展
        val cc = createClient(config) ?: return@withContext null
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: 云端网络操作加 30s 超时
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.download(path) }
    }

    /**
     * 检查云端是否有备份文件。
     */
    suspend fun hasBackup(config: CloudBackupConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        val cc = createClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: 云端网络操作加 30s 超时
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.exists(path) } ?: false
    }

    /**
     * 上传备份(同时上传带时间戳的版本 + latest 版本)。
     * latest 版本用于 downloadLatestBackup 快速恢复。
     */
    suspend fun uploadBackupWithLatest(config: CloudBackupConfig, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        // 先上传带时间戳的归档版本
        val archived = uploadBackup(config, data)
        // 再上传 latest 版本(覆盖)
        val cc = createClient(config) ?: return@withContext archived
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: 云端网络操作加 30s 超时
        val ok2 = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.upload(path, data) } ?: false
        archived || ok2
    }

    // ── v1.132: 列表 / 按版本恢复 / 删除 / 测试连接 ───────────────────────

    /**
     * v1.132: 列出远端所有归档备份(按时间倒序,最新在前)。
     * 通过 PROPFIND(WebDAV)或 ListObjectsV2(S3)拉取前缀目录下所有文件,
     * 过滤出 [BACKUP_FILE_PREFIX] 开头的归档版本(排除 latest 指针文件),
     * 最多返回 [LIST_MAX_ITEMS] 条防止远端目录爆炸。
     *
     * @param config 云备份配置
     * @return 远端备份列表;config 未配置或请求失败时返回空列表
     */
    suspend fun listBackups(config: CloudBackupConfig): List<RemoteBackup> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext emptyList()
        val acc = createAsyncClient(config) ?: return@withContext emptyList()
        // list 传入目录前缀(s3 用 s3KeyPrefix,webdav 用 webdavPath)
        val prefix = when (config.type) {
            "s3" -> config.s3KeyPrefix.trimEnd('/') + "/"
            "webdav" -> config.webdavPath.trimEnd('/') + "/"
            else -> return@withContext emptyList()
        }
        val files = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.list(prefix) } ?: emptyList()
        files
            // 只保留 muse-backup-*.json 归档版本(排除 latest 指针)
            .filter { it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".json") && it.name != LATEST_FILE_NAME }
            // 按修改时间倒序(最新在前);时间戳为 0 时按文件名降序兜底
            .sortedWith(compareByDescending<RemoteFile> { it.lastModified }.thenByDescending { it.name })
            .take(LIST_MAX_ITEMS)
            .map { rf ->
                RemoteBackup(
                    fileName = rf.name,
                    remotePath = rf.path,
                    size = rf.size,
                    lastModified = rf.lastModified,
                )
            }
    }

    /**
     * v1.132: 下载指定文件名的归档备份(用于"按版本恢复")。
     * @param config 云备份配置
     * @param fileName 备份文件名(如 muse-backup-20250719-153000.json)
     * @return 备份 JSON 字节;null 失败或不存在
     */
    suspend fun downloadBackup(config: CloudBackupConfig, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null
        val acc = createAsyncClient(config) ?: return@withContext null
        val path = resolveRemotePath(config, fileName)
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.downloadBytes(path) }
    }

    /**
     * v1.132: 删除指定文件名的归档备份(用于"清理旧版本")。
     * 注意:不允许删除 latest 指针文件,避免破坏快速恢复链路。
     * @param config 云备份配置
     * @param fileName 备份文件名
     * @return true 成功;false 失败、未配置或试图删除 latest
     */
    suspend fun deleteBackup(config: CloudBackupConfig, fileName: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        if (fileName == LATEST_FILE_NAME) {
            Logger.w("CloudBackupService", "拒绝删除 latest 指针文件: $fileName")
            return@withContext false
        }
        val acc = createAsyncClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, fileName)
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.delete(path) } ?: false
    }

    /**
     * v1.132: 测试连接是否可用(用于"测试连接"按钮)。
     * @param config 云备份配置
     * @return true 认证通过且服务端可访问;false 网络异常或认证失败
     */
    suspend fun testConnection(config: CloudBackupConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        val acc = createAsyncClient(config) ?: return@withContext false
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.test() } ?: false
    }
}

/**
 * v1.132: 远端文件元数据(WebDAV / S3 共用)。
 * 由 [WebDavClient.list] / [S3Client.list] 返回,经 [CloudBackupService.listBackups] 映射为 [RemoteBackup]。
 * @param name 文件名(不含路径)
 * @param path 完整路径(S3 为 key,WebDAV 为相对路径)
 * @param size 字节数;无法获取时为 0
 * @param lastModified 修改时间戳(毫秒);无法获取时为 0
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
)

/**
 * v1.132: 远端备份元数据(供 CloudBackupPage 展示列表)。
 * @param fileName 文件名(如 muse-backup-20250719-153000.json)
 * @param remotePath 远端完整路径(S3 为 key,WebDAV 为相对路径)
 * @param size 字节数
 * @param lastModified 修改时间戳(毫秒);服务端未返回时为 0
 */
data class RemoteBackup(
    val fileName: String,
    val remotePath: String,
    val size: Long,
    val lastModified: Long,
)
