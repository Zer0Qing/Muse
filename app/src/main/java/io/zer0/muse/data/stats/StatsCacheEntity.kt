package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 统计聚合缓存表。
 *
 * 缓存 MessageDao 中 6 处全表 GROUP BY 聚合的结果,避免每次进统计页全表扫描。
 *  - key: 聚合维度标识(如 "countByRole" / "countByModel" / "countByAssistant" /
 *    "countByHour" / "topSessions" / "totalMessages" / "totalSessions" 等)
 *  - value: JSON 序列化的聚合结果
 *  - updatedAt: 最后刷新时间,用于判断缓存是否过期
 *
 * 刷新策略:
 *  1. 消息发送/删除后异步刷新(延迟 5s 合并多次写入)
 *  2. WorkManager 每日定时全量刷新
 *  3. 统计页打开时若缓存超过 1 小时则后台刷新,先显示旧缓存
 */
@Entity(tableName = "stats_cache")
data class StatsCacheEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(defaultValue = "{}") val value: String = "{}",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)
