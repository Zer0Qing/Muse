package io.zer0.muse.data.computenode

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Linux 轻量云电脑节点。
 *
 * 一行对应一台已接入的远端主机。连接方式、认证方式与运行状态都以字符串落库，
 * 便于跨版本迁移与备份格式保持稳定；凭据列只保存本机安全存储产出的密文。
 */
@Serializable
@Entity(tableName = "remote_compute_nodes")
data class RemoteComputeNodeEntity(
    @PrimaryKey val nodeId: String,
    @ColumnInfo(defaultValue = "") val name: String = "",
    /** "SSH_TUNNEL" 或 "DIRECT_TLS" */
    @ColumnInfo(defaultValue = "SSH_TUNNEL") val connectionMode: String = "SSH_TUNNEL",
    @ColumnInfo(defaultValue = "") val host: String = "",
    @ColumnInfo(defaultValue = "22") val sshPort: Int = 22,
    @ColumnInfo(defaultValue = "18765") val daemonPort: Int = 18765,
    @ColumnInfo(defaultValue = "") val username: String = "",
    /** "PASSWORD" / "PRIVATE_KEY" / "PAIR_TOKEN" */
    @ColumnInfo(defaultValue = "PASSWORD") val authType: String = "PASSWORD",
    /** 经 SecureKeyStore.encrypt 加密的 SSH 密码或私钥，严禁明文 */
    @ColumnInfo(defaultValue = "") val encryptedCredential: String = "",
    /** 经 SecureKeyStore.encrypt 加密的 muse-node Bearer Token */
    @ColumnInfo(defaultValue = "") val encryptedNodeToken: String = "",
    /** DIRECT_TLS 模式下的叶子证书 SHA256 指纹 */
    @ColumnInfo(defaultValue = "") val tlsFingerprint: String = "",
    @ColumnInfo(defaultValue = "{}") val osInfoJson: String = "{}",
    @ColumnInfo(defaultValue = "[]") val installedPacksJson: String = "[]",
    /** "UNINITIALIZED" / "BOOTSTRAPPING" / "ONLINE" / "OFFLINE" / "ERROR" */
    @ColumnInfo(defaultValue = "UNINITIALIZED") val status: String = "UNINITIALIZED",
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lastSeenAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

/**
 * 连接模式。
 *
 * 数据库列存的是 [wireValue]，枚举只作为内存侧的取值约束；遇到未知字符串时
 * 由调用方显式拒绝，避免坏数据被静默解释成默认连接方式。
 */
@Serializable
enum class ComputeNodeMode(val wireValue: String) {
    SSH_TUNNEL("SSH_TUNNEL"),
    DIRECT_TLS("DIRECT_TLS"),
    ;

    companion object {
        fun fromWire(value: String): ComputeNodeMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 节点生命周期状态。 */
@Serializable
enum class ComputeNodeStatus(val wireValue: String) {
    UNINITIALIZED("UNINITIALIZED"),
    BOOTSTRAPPING("BOOTSTRAPPING"),
    ONLINE("ONLINE"),
    OFFLINE("OFFLINE"),
    ERROR("ERROR"),
    ;

    companion object {
        fun fromWire(value: String): ComputeNodeStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 云电脑节点读写口。
 *
 * 读路径给工作台与心跳轮询共用；写路径只暴露整行 upsert 与状态/默认节点更新，
 * 凭据的加解密由调用方在写库前完成，DAO 不接触明文。
 */
@Dao
interface RemoteComputeNodeDao {

    @Query("SELECT * FROM remote_compute_nodes ORDER BY createdAt ASC")
    suspend fun getAll(): List<RemoteComputeNodeEntity>

    @Query("SELECT * FROM remote_compute_nodes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<RemoteComputeNodeEntity>>

    @Query("SELECT * FROM remote_compute_nodes WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getById(nodeId: String): RemoteComputeNodeEntity?

    @Query("SELECT * FROM remote_compute_nodes WHERE nodeId = :nodeId LIMIT 1")
    fun observeById(nodeId: String): Flow<RemoteComputeNodeEntity?>

    @Query("SELECT * FROM remote_compute_nodes WHERE isDefault = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getDefault(): RemoteComputeNodeEntity?

    @Upsert
    suspend fun upsert(node: RemoteComputeNodeEntity)

    @Upsert
    suspend fun upsertAll(nodes: List<RemoteComputeNodeEntity>)

    @Query(
        "UPDATE remote_compute_nodes SET status = :status, lastSeenAt = :lastSeenAt, updatedAt = :updatedAt " +
            "WHERE nodeId = :nodeId",
    )
    suspend fun updateStatus(nodeId: String, status: String, lastSeenAt: Long, updatedAt: Long): Int

    @Query("UPDATE remote_compute_nodes SET isDefault = 0 WHERE isDefault = 1")
    suspend fun clearDefault()

    @Query("UPDATE remote_compute_nodes SET isDefault = 1, updatedAt = :updatedAt WHERE nodeId = :nodeId")
    suspend fun markDefault(nodeId: String, updatedAt: Long): Int

    @Query("DELETE FROM remote_compute_nodes WHERE nodeId = :nodeId")
    suspend fun deleteById(nodeId: String)

    @Query("DELETE FROM remote_compute_nodes")
    suspend fun deleteAll()
}
