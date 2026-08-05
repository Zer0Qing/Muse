package io.zer0.muse.data.session

import android.content.Context

/**
 * P2-1 拆库门面。
 *
 * 当前只托管 MuseDb（保持单库运行，零行为变化）；后续拆出
 * KnowledgeDb / ScheduleDb / AgentDb / StatsDb 时，统一通过本门面获取，
 * 避免业务代码直接依赖“单库全局单例”。
 */
object MuseDatabases {

    @Volatile
    private var museDb: MuseDb? = null

    /** 核心对话库（当前唯一实例）。 */
    fun muse(context: Context): MuseDb {
        return museDb ?: synchronized(this) {
            museDb ?: MuseDb.get(context).also { museDb = it }
        }
    }

    /** 进程结束清理（测试/工具用）。 */
    fun resetForTests() {
        synchronized(this) {
            museDb = null
        }
    }
}
