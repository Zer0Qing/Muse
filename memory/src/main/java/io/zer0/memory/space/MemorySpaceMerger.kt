package io.zer0.memory.space

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.compile.SpaceFileMergeResult
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.ProfileSnapshotDao
import io.zer0.memory.fact.ProfileSnapshotEntity
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.runtime.MemoryProjectionStatus
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.memory.summary.MemoryDb
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/**
 * 删除 Space 时可用的数据面。
 *
 * 每项都可空：生产装配（memoryModule）全部给齐；最小构造（只测 Space 列表的单测）可以都不给，
 * 缺哪一项就跳过对应步骤并记一行日志 —— 不假装合并过，也不因为缺依赖就拒绝删除。
 */
class MemorySpaceMergeDeps(
    /** 需要合并的全部 FactDb（主库 + 磁盘上真实存在的助手分库）。调用时机由仓库决定。 */
    val factDbs: () -> List<FactDb> = { emptyList() },
    /** 摘要 / 编译产物 / 每日进度 / Dream 所在的库。 */
    val memoryDb: MemoryDb? = null,
    /** 文件投影（memory/{scope}/{space}/）。 */
    val fileWriter: MemoryFileWriter? = null,
    /** 矛盾清单（scope|space 分 key 的 JSON）。 */
    val contradictions: MemoryContradictionStore? = null,
    /** FACTS 段确定性重建（不需要模型）。 */
    val compiler: MemoryCompiler? = null,
    /** 备份恢复期间必须拒绝合并：库会被整体替换，写进旧文件等于白做。 */
    val writeGate: MemoryWriteGate? = null,
)

/** 一次 Space 合并实际做了多少事，仅用于日志与测试断言。 */
data class SpaceMergeReport(
    val factRowsMoved: Int = 0,
    val linkRowsMoved: Int = 0,
    val tombstonesMoved: Int = 0,
    val profileSections: Int = 0,
    val projectionsMoved: Int = 0,
    val summariesMoved: Int = 0,
    val compiledSectionsDropped: Int = 0,
    val dailyStatesCleared: Int = 0,
    val dreamRowsDeleted: Int = 0,
    val contradictionsMerged: Int = 0,
    val dailyFilesMerged: Int = 0,
    val dailyFilesMoved: Int = 0,
)

/**
 * 把一个 Space 的内容合并进 default。
 *
 * 原则：**内容保留，派生物重建**。
 *  - 事实、删除标记、画像、摘要、矛盾清单是内容 → 迁走/并入，不丢；
 *  - FACTS 段是事实表的确定性投影 → 清空后用合并后的表重建；
 *  - TODAY/WEEK/LONGTERM、daily_state、Dream rollback 点是派生物 → 删掉或标失效，
 *    下一轮编译重新生成（保留旧文本只会让「按另一个空间算出来的结论」混进 default）；
 *  - daily/ 下的日记保存的是原始日摘要、删了无法重建 → 按日期并进 default，保住 6 日窗口。
 *
 * 每一步都可重复执行：事实/link 的 UPDATE 第二次匹配 0 行；墓碑 INSERT OR IGNORE；
 * 画像逐段合并；段落删除按 space 幂等；daily_state 按 key 删除；清单合并按 key 去重。
 * 因此调用方不需要事务「全成功或全失败」，失败后重试即可。
 */
class MemorySpaceMerger(
    private val deps: MemorySpaceMergeDeps,
) {

    suspend fun mergeIntoDefault(sourceSpaceId: String, databases: List<FactDb>): SpaceMergeReport {
        val source = sourceSpaceId.trim()
        require(source.isNotEmpty()) { "sourceSpaceId 不能为空" }
        require(source != MemorySpaceEntity.DEFAULT_SPACE_ID) {
            "default 空间不能作为合并源（它自己就是目标）"
        }

        val counter = Counter()
        // 必须在迁移之前收集：facts 一旦改了 space_id，这个空间里的 scope 就查不到了。
        val scopes = collectAffectedScopes(source, databases)
        for (db in databases) {
            mergeFactsDatabase(db, source, counter)
        }
        mergeMemoryDatabase(source, scopes, counter)
        mergeContradictions(source, counter)
        mergeFiles(source, counter)

        val report = counter.toReport()
        Logger.i(
            TAG,
            "Space 合并完成: $source → ${MemorySpaceEntity.DEFAULT_SPACE_ID} " +
                "(facts=${report.factRowsMoved} links=${report.linkRowsMoved} " +
                "tombstones=${report.tombstonesMoved} profiles=${report.profileSections} " +
                "summaries=${report.summariesMoved} sections=${report.compiledSectionsDropped} " +
                "dailyState=${report.dailyStatesCleared} dream=${report.dreamRowsDeleted} " +
                "contradictions=${report.contradictionsMerged} " +
                "dailyFiles=${report.dailyFilesMerged}+${report.dailyFilesMoved})",
        )
        return report
    }

    /**
     * 受影响的记忆作用域。
     *
     * 事实表是「这个空间里到底有哪些作用域有内容」的权威口径；摘要、画像、段落作为补充，
     * 覆盖「只有摘要、还没有事实」的情形（否则那些 scope 的默认空间段落不会被重建）。
     */
    private suspend fun collectAffectedScopes(source: String, databases: List<FactDb>): Set<String> {
        val scopes = linkedSetOf<String>()
        for (db in databases) {
            scopes += db.memorySpaceDao().distinctFactScopes(source)
            db.profileSnapshotDao().getAllBySpace(source).forEach { row -> scopes += row.scope }
        }
        deps.memoryDb?.let { db ->
            scopes += db.scopedCompiledSectionDao().scopesForSpace(source)
            db.sessionSummaryDao().assistantIdsForSpace(source).forEach { assistantId ->
                scopes += scopeOfAssistant(assistantId)
            }
        }
        return scopes
    }

    /** facts 只改 space_id 就能迁：行 id 与正文不变，FTS 索引（fact_id + ngram）无需重建。 */
    private suspend fun mergeFactsDatabase(db: FactDb, source: String, counter: Counter) {
        counter.factRowsMoved += db.memorySpaceDao().migrateFacts(source, MemorySpaceEntity.DEFAULT_SPACE_ID)
        counter.linkRowsMoved += db.memoryLinkDao().migrateBySpaceId(source, MemorySpaceEntity.DEFAULT_SPACE_ID)
        counter.projectionsMoved += db.factProjectionDao().migrateSpace(source, MemorySpaceEntity.DEFAULT_SPACE_ID)

        // 墓碑的唯一键是 (scope, space, normalized_fact)：default 里已有同一条时 UPDATE 会撞键，
        // 所以固定顺序是「先 INSERT OR IGNORE 逐条搬 → 再删源」。
        val tombstones = db.factTombstoneDao().getAllForSpace(source)
        for (row in tombstones) {
            db.factTombstoneDao().insertIfAbsent(row.copy(id = 0, spaceId = MemorySpaceEntity.DEFAULT_SPACE_ID))
        }
        counter.tombstonesMoved += tombstones.size
        if (tombstones.isNotEmpty()) {
            db.factTombstoneDao().deleteBySpace(source)
        }
        mergeProfileSnapshots(db.profileSnapshotDao(), source, counter)
    }

    /**
     * 画像按 (scope, space, section) 合并：目标没有就整段搬，目标有就逐段合并。
     *
     * 覆写（manual_content）优先留目标的：用户手改的是「他想要的样子」，
     * 源空间那份自动生成或更早的覆写不该盖掉它；目标没覆写才取源的。
     */
    private suspend fun mergeProfileSnapshots(dao: ProfileSnapshotDao, source: String, counter: Counter) {
        val sourceRows = dao.getAllBySpace(source)
        if (sourceRows.isEmpty()) return
        for (row in sourceRows) {
            val existing = dao.getOne(row.scope, MemorySpaceEntity.DEFAULT_SPACE_ID, row.section)
            if (existing == null) {
                // id 归零让自增重新分配：直接搬源 id 会和 default 已有行撞主键。
                dao.upsert(row.copy(id = 0, spaceId = MemorySpaceEntity.DEFAULT_SPACE_ID))
            } else {
                dao.upsert(mergeProfileSection(existing, row))
            }
            counter.profileSections += 1
        }
        dao.deleteBySpace(source)
    }

    private fun mergeProfileSection(existing: ProfileSnapshotEntity, incoming: ProfileSnapshotEntity): ProfileSnapshotEntity {
        val newer = if (isNewer(incoming.updatedAt, existing.updatedAt)) incoming else existing
        return existing.copy(
            manualContent = existing.manualContent ?: incoming.manualContent,
            autoContent = newer.autoContent,
            evidenceFingerprint = newer.evidenceFingerprint,
            sourceFactIds = mergeSourceFactIds(existing.sourceFactIds, incoming.sourceFactIds),
            updatedAt = newer.updatedAt,
            lastRefreshAt = newer.lastRefreshAt,
        )
    }

    /**
     * 时间戳新旧判定。
     *
     * 解析不了（历史数据/手改）时退回字符串比较：ISO 8601 同格式下字典序等于时间序，
     * 比「解析失败就当最新/最旧」更接近事实。
     */
    private fun isNewer(candidate: String, baseline: String): Boolean {
        val left = runCatching { Instant.parse(candidate) }.getOrNull()
        val right = runCatching { Instant.parse(baseline) }.getOrNull()
        return if (left != null && right != null) left.isAfter(right) else candidate > baseline
    }

    /** source_fact_ids 是 JSON 字符串数组：合并时按 id 去重并保持先出现的顺序。 */
    private fun mergeSourceFactIds(existing: String, incoming: String): String {
        val incomingIds = decodeFactIds(incoming)
        if (incomingIds.isEmpty()) return existing
        val merged = (decodeFactIds(existing) + incomingIds).distinct()
        return AppJson.encodeToString(ListSerializer(String.serializer()), merged)
    }

    private fun decodeFactIds(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching { AppJson.decodeFromString(ListSerializer(String.serializer()), text) }
            .getOrElse {
                // 坏掉的只可能是「看依据」这一个展示字段：宁可丢这一侧的 id，也不能让整段画像合并失败
                // —— 正文才是用户看到、注入用到的东西。
                Logger.w(TAG, "画像来源 id 列表解不开，合并时按空处理: $text")
                emptyList()
            }
    }

    private suspend fun mergeMemoryDatabase(source: String, scopes: Set<String>, counter: Counter) {
        val db = deps.memoryDb
        if (db == null) {
            Logger.w(TAG, "Space 合并: 未装配 MemoryDb，跳过摘要/编译产物/每日进度/Dream")
            return
        }
        counter.summariesMoved += db.sessionSummaryDao().migrateSpace(source, MemorySpaceEntity.DEFAULT_SPACE_ID)
        mergeCompiledSections(db, source, scopes, counter)
        clearDailyStates(db, source, scopes, counter)
        deleteDreamRollbackPoints(db, source, counter)
    }

    private suspend fun mergeCompiledSections(db: MemoryDb, source: String, scopes: Set<String>, counter: Counter) {
        val dao = db.scopedCompiledSectionDao()
        val now = Instant.now().toString()
        // 源空间的段落是它自己的派生物：Space 都没了就不该再留，
        // 把旧文本搬进 default 只会让「按另一个空间算出来的结论」混进默认空间的注入内容。
        counter.compiledSectionsDropped += dao.deleteForSpace(source)
        for (scope in scopes) {
            // FACTS 是事实表的确定性投影：清空内容后立刻用合并后的表重建，内容不会丢。
            dao.clearByKey(MemoryCompiler.Section.FACTS.key, scope, MemorySpaceEntity.DEFAULT_SPACE_ID, now)
            // TODAY/WEEK/LONGTERM 没有可立即重建的数据源：标失效让界面与 Dream 快照都不再信任旧文本，
            // 内容留到下一轮编译覆盖。
            dao.markStatus(
                scope = scope,
                spaceId = MemorySpaceEntity.DEFAULT_SPACE_ID,
                sectionKeys = REBUILDABLE_SECTION_KEYS,
                status = MemoryProjectionStatus.STALE.name,
                now = now,
            )
            val compiler = deps.compiler
            if (compiler == null) {
                Logger.w(TAG, "Space 合并: 未装配 MemoryCompiler，default 的 FACTS 段留给下一轮编译重建(scope=$scope)")
            } else {
                compiler.rebuildFactsSection(scope, MemorySpaceEntity.DEFAULT_SPACE_ID)
            }
        }
    }

    /**
     * 每日进度按 `scope|space` 分行存：源空间与其在 default 的对应行都要清掉，
     * 否则合并后的 default 会带着「今天已经编译完」的完成标记跳过重编，新并进来的内容当天不出现。
     */
    private suspend fun clearDailyStates(db: MemoryDb, source: String, scopes: Set<String>, counter: Counter) {
        val dao = db.dailyStateDao()
        val keys = dao.allKeys().filter { key -> shouldClearDailyState(key, source, scopes) }
        for (key in keys) {
            counter.dailyStatesCleared += dao.delete(key)
        }
    }

    private fun shouldClearDailyState(key: String, source: String, scopes: Set<String>): Boolean = when {
        key == MemorySpaceEntity.DEFAULT_SPACE_ID -> MAIN_SCOPE in scopes
        key.endsWith("|$source") -> true
        !key.endsWith("|${MemorySpaceEntity.DEFAULT_SPACE_ID}") -> false
        else -> key.substringBeforeLast('|') in scopes
    }

    /**
     * Dream 回滚点存的是合并前的段落快照：留着它，用户在 Dream 页点一次回滚
     * 就会用旧快照盖掉刚并进 default 的内容。源与目标两边都要清。
     */
    private suspend fun deleteDreamRollbackPoints(db: MemoryDb, source: String, counter: Counter) {
        val dao = db.memoryDreamDao()
        counter.dreamRowsDeleted += dao.deleteStatesBySpace(source)
        counter.dreamRowsDeleted += dao.deleteRevisionsBySpace(source)
        counter.dreamRowsDeleted += dao.deleteStatesBySpace(MemorySpaceEntity.DEFAULT_SPACE_ID)
        counter.dreamRowsDeleted += dao.deleteRevisionsBySpace(MemorySpaceEntity.DEFAULT_SPACE_ID)
    }

    private suspend fun mergeContradictions(source: String, counter: Counter) {
        val store = deps.contradictions
        if (store == null) {
            Logger.w(TAG, "Space 合并: 未装配矛盾清单，跳过")
            return
        }
        counter.contradictionsMerged += store.mergeSpaceInto(source, MemorySpaceEntity.DEFAULT_SPACE_ID)
    }

    private fun mergeFiles(source: String, counter: Counter) {
        val writer = deps.fileWriter
        if (writer == null) {
            Logger.w(TAG, "Space 合并: 未装配文件投影，跳过 memory/{scope}/ 目录")
            return
        }
        when (val result = writer.mergeSpaceIntoDefault(source, MemorySpaceEntity.DEFAULT_SPACE_ID)) {
            is SpaceFileMergeResult.Merged -> {
                counter.dailyFilesMerged += result.mergedDates
                counter.dailyFilesMoved += result.movedDates
                if (result.failedDates > 0) {
                    Logger.w(TAG, "Space 合并: ${result.failedDates} 天的 daily 没并进去，源目录已保留供重试")
                }
            }

            is SpaceFileMergeResult.Rejected ->
                Logger.w(TAG, "Space 合并: 文件投影跳过(${result.reason})")
        }
    }

    /** 助手 id → 记忆作用域：主助手落在 "main"，其余助手用自己的 id。 */
    private fun scopeOfAssistant(assistantId: String): String {
        val id = assistantId.trim()
        return if (id.isEmpty() || id == MemorySpaceEntity.DEFAULT_SPACE_ID || id == MAIN_SCOPE) MAIN_SCOPE else id
    }

    /** 可变累加器：报告是对外只读快照，内部逐步累加。字段放 body 而不是构造参数，避免一长串同类型数字换位。 */
    private class Counter {
        var factRowsMoved = 0
        var linkRowsMoved = 0
        var tombstonesMoved = 0
        var profileSections = 0
        var projectionsMoved = 0
        var summariesMoved = 0
        var compiledSectionsDropped = 0
        var dailyStatesCleared = 0
        var dreamRowsDeleted = 0
        var contradictionsMerged = 0
        var dailyFilesMerged = 0
        var dailyFilesMoved = 0

        fun toReport(): SpaceMergeReport = SpaceMergeReport(
            factRowsMoved = factRowsMoved,
            linkRowsMoved = linkRowsMoved,
            tombstonesMoved = tombstonesMoved,
            profileSections = profileSections,
            projectionsMoved = projectionsMoved,
            summariesMoved = summariesMoved,
            compiledSectionsDropped = compiledSectionsDropped,
            dailyStatesCleared = dailyStatesCleared,
            dreamRowsDeleted = dreamRowsDeleted,
            contradictionsMerged = contradictionsMerged,
            dailyFilesMerged = dailyFilesMerged,
            dailyFilesMoved = dailyFilesMoved,
        )
    }

    private companion object {
        const val TAG = "MemorySpaceMerger"

        /** 主助手的作用域 id。 */
        const val MAIN_SCOPE = "main"

        /**
         * 合并后需要「重建而不是保留」的段落：FACTS 由事实表重建，这三段等下一轮编译。
         */
        val REBUILDABLE_SECTION_KEYS = listOf(
            MemoryCompiler.Section.TODAY.key,
            MemoryCompiler.Section.WEEK.key,
            MemoryCompiler.Section.LONGTERM.key,
        )
    }
}
