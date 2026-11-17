package io.zer0.muse.data.runtime

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.zer0.muse.runtime.RuntimeId

/**
 * 会话内的运行环境安装卡。
 *
 * 装运行环境这件事的真相有两处：磁盘上的 `current.json`（由 `RuntimeStore` 负责）
 * 和「用户在某条消息上看到过这张卡、并且选了以后再说」。后者必须落库，否则重启后
 * 同一条 runtime_missing 又会把卡片推回输入栏上方，用户刚做的选择被无声吞掉。
 *
 * 主键取触发它的那条消息 id：一条缺失消息对应一张卡，重复写入只更新状态，不会堆出多张。
 */
@Entity(tableName = "runtime_install_cards")
data class RuntimeInstallCardEntity(
    /** 触发这张卡的那条工具结果消息 id。 */
    @PrimaryKey
    val messageId: String,

    /** 需要安装的运行环境组件。 */
    val runtimeId: RuntimeId,

    /** 目标包版本（清单 revision）；清单还没取到时为空串，表示"还不知道要装哪个版本"。 */
    val version: String,

    /** 卡片状态；与安装进度一一对应，UI 直接据此选四态之一。 */
    val state: RuntimeInstallCardState,

    /** 下载进度百分比（0-100）；非下载态固定为 0。 */
    val progress: Int,

    /** 失败类型（宿主可判定的短码，例如 no_space / checksum / self_check）。 */
    val errorKind: String?,

    /** 用户是否选了"以后再说"。 */
    val dismissed: Boolean,

    /** 最后更新时间（epoch millis）。 */
    val updatedAt: Long,
)

/**
 * 安装卡的状态机。取值与安装链路的阶段严格对齐，不额外发明状态：
 * 卡片显示的从来不是"我以为到哪一步"，而是安装器真实上报的那一步。
 */
enum class RuntimeInstallCardState {
    OFFERED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    READY,
    FAILED,
    ;

    /** 仍在进行中的状态：这些状态下用户退出页面也不该丢卡片。 */
    val isActive: Boolean
        get() = this == DOWNLOADING || this == VERIFYING || this == EXTRACTING
}
