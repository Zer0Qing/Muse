package io.zer0.memory

import androidx.room.Room
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * memory 模块的 Koin 装配。
 *
 * 依赖关系:
 *  - Room: MemoryDb(摘要/状态/编译产物) + FactDb(FTS5 元事实)
 *  - [io.zer0.memory.llm.MemoryLlmClient] 由 app 模块提供实现(委托给 ChatService)
 *  - [CoroutineScope] 由 app 模块提供(application scope,ticker 后台任务用)
 *
 * 注意: [MemoryTicker] 由 app 模块注册(需要读 SettingsRepository 的 memory 开关),
 * 这里只注册数据层 + 核心服务。
 */
val memoryModule: Module = module {

    // ── Room 数据库 ──
    single {
        Room.databaseBuilder(androidContext(), MemoryDb::class.java, "memory.db")
            // v1.78 (M4): 移除 upgrade 的 destructive migration,避免升级时静默清空用户数据;
            // 仅保留降级保护(从历史更高版本降到当前 v1 时不崩溃)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }

    // v0.22: per-assistant facts.db 提供者(按 assistantId 创建/缓存独立 FactDb)
    single { io.zer0.memory.fact.FactDbProvider(androidContext()) }

    // v1.78 (M12): 全局 FactDb 复用 FactDbProvider 的 "default" 实例
    // 避免 FactDb.create 与 FactDbProvider 各建一个 Room 实例指向同一 facts.db 文件
    single {
        get<io.zer0.memory.fact.FactDbProvider>().getFactDb("default")
    }

    // ── DAO ──
    single { get<MemoryDb>().sessionSummaryDao() }
    single { get<MemoryDb>().dailyStateDao() }
    single { get<MemoryDb>().compiledSectionDao() }
    single { get<FactDb>().factDao() }

    // ── 核心服务 ──
    single { SessionSummaryManager(get(), get()) }   // dao + llmClient
    // v6: 记忆编译产物同时输出到文件系统(memory.md + daily/)
    single { io.zer0.memory.compile.MemoryFileWriter(androidContext().filesDir) }
    single { MemoryCompiler(get(), get(), get()) }    // sectionDao + llmClient + fileWriter
    single { FactStore(get()) }                       // factDao
    single { DeepMemoryProcessor(get<io.zer0.memory.fact.FactDbProvider>(), get()) }  // factDbProvider + llmClient

    // 置顶记忆存储(openhanako pinned-memory-store.ts)
    single { io.zer0.memory.pin.PinnedMemoryStore(java.io.File(androidContext().filesDir, "pinned_memory")) }
}
