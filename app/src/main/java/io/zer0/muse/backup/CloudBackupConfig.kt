package io.zer0.muse.backup

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 8.9: 云备份配置(S3 + WebDAV 二选一)。
 *
 * 存储在 SettingsRepository 的 KEY_CLOUD_BACKUP_CONFIG 中,JSON 格式。
 * type=none 表示未配置云备份;type=s3 用 S3Client;type=webdav 用 WebDavClient。
 *
 * H-001: s3SecretKey / webdavPassword 涉及敏感凭据,经 SettingsRepository 序列化为 JSON
 * 落盘前应走 [SecureKeyStore] 加密。本数据类只承载字段定义,加解密通过 [encrypted] /
 * [decrypted] 成员方法在序列化边界完成:写入 DataStore 前 `encrypted()`,读出后 `decrypted()`。
 */
@Serializable
data class CloudBackupConfig(
    val type: String = "none", // none / s3 / webdav
    // S3 配置
    val s3Endpoint: String = "",
    val s3Region: String = "",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3KeyPrefix: String = "muse/", // 对象 key 前缀(目录)
    // WebDAV 配置
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val webdavPath: String = "/muse/", // 远程目录
    // 通用
    val autoSync: Boolean = false, // 是否自动同步(定时上传)
    /** v1.98: 自动同步间隔(小时)。默认 24 小时。仅当 autoSync=true 时生效。 */
    val autoSyncIntervalHours: Int = 24,
    val lastSyncAt: Long = 0, // 上次同步时间戳
    /** v1.120: 云备份加密密码(用户可设)。非空时云备份用 AES-256-GCM 加密(PBKDF2 派生密钥),跨设备可用此密码解密。 */
    val backupPassword: String = "",
) {
    val isConfigured: Boolean get() = when (type) {
        "s3" -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() && s3AccessKey.isNotBlank() && s3SecretKey.isNotBlank()
        "webdav" -> webdavUrl.isNotBlank() && webdavUsername.isNotBlank()
        else -> false
    }

    /**
     * H-001: 返回 s3SecretKey / webdavPassword 已加密(走 [SecureKeyStore.encrypt])的副本,
     * 供持久化前调用。空值原样保留(不加密空值);已加密(有 enc_v1: 前缀)原样保留。
     */
    suspend fun encrypted(): CloudBackupConfig = copy(
        s3SecretKey = SecureKeyStore.encrypt(s3SecretKey),
        webdavPassword = SecureKeyStore.encrypt(webdavPassword),
        backupPassword = SecureKeyStore.encrypt(backupPassword),
    )

    /**
     * H-001: 返回 s3SecretKey / webdavPassword 已解密(走 [SecureKeyStore.decrypt])的副本,
     * 供从持久化层读出后调用。旧版明文字段由 decrypt 透传(迁移兼容)。
     */
    suspend fun decrypted(): CloudBackupConfig = copy(
        s3SecretKey = SecureKeyStore.decrypt(s3SecretKey),
        webdavPassword = SecureKeyStore.decrypt(webdavPassword),
        backupPassword = SecureKeyStore.decrypt(backupPassword),
    )
}
