package io.zer0.memory.fact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * v2.6.23(M4): 记忆账本审计 —— 重复/无证据/孤儿事实从「靠猜」变成可查。
 *
 * 定位与 S4 诊断卡同一条:**内部可查,不直接展示给用户**。用户不需要在自己的记忆页里
 * 看到「你有 3 条事实没有依据」这种话;但当用户报「它记错了/它把两件事混成一件」时,
 * 这组计数是唯一能把「疑似账本问题」和「模型理解问题」分开的东西。
 *
 * 因此这里的产出**全部是整数**:审计结果会进诊断导出 JSON,而导出是被粘贴到聊天里发给人的。
 * 计数没有隐私代价,「哪几条」有。
 */
data class FactAuditReport(
    /** 实际读到的库数(主库 + 打开成功的分库)。 */
    val databasesChecked: Int = 0,
    val totalFacts: Int = 0,
    /** 没有任何原话证据的事实条数。存量迁移来的本就为 null,所以这是「需要人看一眼」而不是「错」。 */
    val withoutEvidence: Int = 0,
    /** 同一 entity_key 下挂着多条不同断言的实体数 —— 重复/打架的候选。 */
    val duplicateEntities: Int = 0,
    /** supersedes_id 指向已不存在的行。 */
    val danglingSupersedes: Int = 0,
    /** merged_into_id 指向已不存在的行(合并目标被删,子事实失去归属)。 */
    val danglingMergedInto: Int = 0,
    /** 等待用户裁决的矛盾条数。 */
    val pendingConflicts: Int = 0,
    /** 已过 expires_at 却仍在表里(清扫器没跑或跑失败)。 */
    val expiredNotSwept: Int = 0,
) {
    /** 有没有值得人看的东西:全零时诊断卡上连这一行都不该出现。 */
    val hasFindings: Boolean
        get() = withoutEvidence > 0 || duplicateEntities > 0 || danglingSupersedes > 0 ||
            danglingMergedInto > 0 || pendingConflicts > 0 || expiredNotSwept > 0

    companion object {
        val EMPTY = FactAuditReport()
    }
}

/**
 * 审计数据源。与 [io.zer0.muse.web.HostSystemStatusSource] 同构:
 * 未装配时照常回执一份全零报告,而不是让诊断卡打不开。
 */
interface MemoryAuditSource {

    suspend fun audit(now: Instant = Instant.now()): FactAuditReport

    object EMPTY : MemoryAuditSource {
        override suspend fun audit(now: Instant): FactAuditReport = FactAuditReport.EMPTY
    }
}

/**
 * 按**磁盘实存**的分库遍历审计。
 *
 * 只用 `listAssistantDbIds()`:它只列文件已在的库,所以审计不会凭助手 id 猜一遍、
 * 顺手在磁盘上建出空库(那是 v1.78 专门修过的坑)。
 *
 * 单库读取失败表现为**该库不计入 [FactAuditReport.databasesChecked]**,
 * 其余库照常出数 —— 诊断工具因为一个库打不开就整体缺席,读起来像「账本很干净」,
 * 这是最坏的失效形态。
 */
class ProviderMemoryAuditSource(
    private val provider: FactDbProvider,
    private val assistantIds: () -> List<String> = { provider.listAssistantDbIds() },
) : MemoryAuditSource {

    override suspend fun audit(now: Instant): FactAuditReport = withContext(Dispatchers.IO) {
        val ids = (listOf(DEFAULT_ASSISTANT_KEY) + assistantIds()).distinct()
        aggregate(ids.mapNotNull { id -> runCatching { provider.getFactDb(id).factDao() }.getOrNull() }, now)
    }

    companion object {
        /** 与 [FactDbProvider] 的「主库」键一致:空 id 与 "default" 都归到 facts.db。 */
        const val DEFAULT_ASSISTANT_KEY = "default"

        /**
         * 多库聚合:逐项相加(各分库的 id 不重叠),`databasesChecked` 记的是**真正读到的库数**。
         *
         * 打不开的库在这里被丢掉而不是让整份审计消失 —— 一个库损坏就报不出数,
         * 读导出的人会把「审计缺席」当成「账本干净」。
         */
        internal suspend fun aggregate(daos: List<FactDao>, now: Instant): FactAuditReport {
            var acc = FactAuditReport.EMPTY
            for (dao in daos) {
                acc += auditOne(dao, now)
            }
            return acc
        }

        /**
         * 单库计数(纯查询 + 一个时间戳列),便于用内存库把每一项逐条钉住。
         *
         * 过期判定在 Kotlin 侧用 `Instant.parse`,与 `FactStore.isExpired` 同一口径:
         * 写入侧的 `Instant.now().toString()` 秒小数位长度不固定,在 SQL 里按字符串比会排错序;
         * 解析失败的时间戳**不算过期**(同样跟着 isExpired 的从严取向)。
         */
        internal suspend fun auditOne(dao: FactDao, now: Instant): FactAuditReport {
            val expired = dao.allExpiryTimestamps().count { raw ->
                runCatching { Instant.parse(raw).isBefore(now) }.getOrDefault(false)
            }
            return FactAuditReport(
                databasesChecked = 1,
                totalFacts = dao.count(),
                withoutEvidence = dao.countWithoutEvidence(),
                duplicateEntities = dao.countDuplicateEntities(),
                danglingSupersedes = dao.countDanglingSupersedes(),
                danglingMergedInto = dao.countDanglingMergedInto(),
                pendingConflicts = dao.countPendingConflicts(),
                expiredNotSwept = expired,
            )
        }

        /** 聚合用的逐字段相加;`EMPTY + EMPTY` 仍是 0 个库。 */
        private operator fun FactAuditReport.plus(other: FactAuditReport): FactAuditReport = FactAuditReport(
            databasesChecked = databasesChecked + other.databasesChecked,
            totalFacts = totalFacts + other.totalFacts,
            withoutEvidence = withoutEvidence + other.withoutEvidence,
            duplicateEntities = duplicateEntities + other.duplicateEntities,
            danglingSupersedes = danglingSupersedes + other.danglingSupersedes,
            danglingMergedInto = danglingMergedInto + other.danglingMergedInto,
            pendingConflicts = pendingConflicts + other.pendingConflicts,
            expiredNotSwept = expiredNotSwept + other.expiredNotSwept,
        )
    }
}
