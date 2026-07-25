package io.zer0.memory.ticker

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln

/**
 * v0.32: 记忆系统高级配置(对照 openhanako 的 memory.* 配置)。
 *
 * 灵感来自 openhanako config.example.yaml:
 *   memory:
 *     enabled: true
 *     token_budget: 2500
 *     decay_per_day: 0.02
 *     hit_bonus: 5
 *     base_importance: 10
 *     compile_threshold: 4.5
 *     forget_speed: 1.0
 *
 * Muse 把这套数值参数从硬编码提升为可配置项,让用户能:
 *  - 调整 memory.md 的 token 预算(影响注入到 system prompt 的记忆量)
 *  - 控制遗忘速度(decay_per_day λ,越大忘得越快)
 *  - 调整命中加成(hit_bonus,常提起的记忆不易消失)
 *  - 设置编译阈值(compile_threshold,低于此分的记忆不进入 memory.md)
 *  - 设置遗忘倍率(forget_speed,1.0 正常,2.0 忘得快一倍)
 *
 * 存储位置:DataStore 的 memory_config_json(由 SettingsRepository 持久化)。
 * 由 MemoryTicker 在每次调度时读取,影响 score 衰减与编译选择。
 */
@Serializable
data class MemoryConfig(
    /** memory.md 的目标 token 预算(影响注入到 system prompt 的长度)。 */
    val tokenBudget: Int = 2500,
    /**
     * 高斯衰减系数 λ(days^-1)。
     * - 0.01 → 约 19 天归零(慢,记忆广)
     * - 0.02 → 约 14 天归零(默认)
     * - 0.04 → 约 10 天归零(快,聚焦近期)
     */
    val decayPerDay: Float = 0.02f,
    /**
     * 每次命中加多少分(被 hit 过的记忆重置衰减起点)。
     *
     * v7: 已接入 [factScore] / [cutoffDays] 与 [FactStore.applyDecay]。
     * 被引用/合并的事实会更新 last_hit_at,并以 (baseImportance + hitBonus) 作为有效基础分,
     * 从而衰减更慢、更难被淘汰。
     */
    val hitBonus: Float = 5f,
    /** 新记忆的默认初始分。 */
    val baseImportance: Float = 10f,
    /** 分数低于此值的记忆不进入 memory.md。 */
    val compileThreshold: Float = 4.5f,
    /** 遗忘倍率(1.0 = 正常,2.0 = 忘得快一倍)。 */
    val forgetSpeed: Float = 1.0f,
) {

    companion object {

        /**
         * 计算一条 fact 在给定年龄下的衰减分数。
         *
         * 衰减模型(简化版,与 openhanako 同向但不追求完全一致):
         *  score = effectiveBase * exp(-λ * ageDays)
         *  其中 λ = decayPerDay * forgetSpeed
         *  effectiveBase = baseImportance + (hit ? hitBonus : 0)
         *
         * @param ageDays fact 自创建/命中后过去的天数(>=0)
         * @param config 记忆配置
         * @param hit 是否已命中(命中后 effectiveBase 更高)
         */
        fun factScore(ageDays: Float, config: MemoryConfig, hit: Boolean = false): Float {
            val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
            val base = (config.baseImportance + if (hit) config.hitBonus else 0f).toDouble()
            return (base * exp(-lambda * ageDays.toDouble())).toFloat()
        }

        /**
         * 计算 fact 衰减到 [compileThreshold] 所需的天数。
         * 用于 FactStore.applyDecay 决定何时删除一条 fact。
         *
         * 推导:score = compileThreshold
         *  effectiveBase * exp(-λ * d) = compileThreshold
         *  d = ln(effectiveBase / compileThreshold) / λ
         *
         * 对默认值 (10/4.5, 0.02, 1.0) 约 40 天;decayPerDay=0.04 → 20 天;
         * forgetSpeed=2.0 → 20 天。命中后 effectiveBase 变大,保留天数更长。
         *
         * @param hit 是否已命中
         */
        fun cutoffDays(config: MemoryConfig, hit: Boolean = false): Float {
            val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
            if (lambda <= 0.0) return Float.MAX_VALUE
            val base = (config.baseImportance + if (hit) config.hitBonus else 0f).toDouble()
            val thr = config.compileThreshold.toDouble()
            if (base <= thr) return 0f  // base 已低于阈值,立即全部淘汰
            return (ln(base / thr) / lambda).toFloat()
        }

        /**
         * v1.78: 安全版本的 cutoffDays,用于实际 fact 衰减计算。
         *
         * `cutoffDays` 在 lambda<=0 时返回 `Float.MAX_VALUE`,直接传给
         * `Instant.now().minus(cutoff.toLong(), DAYS)` 会因 `Long.MAX_VALUE` 溢出抛
         * `DateTimeException`。这里返回有限上限 36500 天(100 年),语义等价于"不衰减"。
         */
        fun safeCutoffDays(config: MemoryConfig, hit: Boolean = false): Float {
            val c = cutoffDays(config, hit)
            return when {
                c.isInfinite() || c.isNaN() -> 36500f
                c >= Float.MAX_VALUE / 2 -> 36500f
                else -> c
            }
        }

        /**
         * v1.78: 校验配置数值是否合法,防止 threshold>=base 导致全部 fact 被清空等危险配置。
         * 返回 null 表示合法,否则返回错误描述。
         */
        fun validate(config: MemoryConfig): String? {
            if (config.tokenBudget < 0) return ErrorCode.MEMORY_TOKEN_BUDGET_INVALID.toMessage("negative", config.tokenBudget)
            if (config.tokenBudget == 0) return ErrorCode.MEMORY_TOKEN_BUDGET_INVALID.toMessage("zero")
            if (config.decayPerDay < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("decayPerDay_negative", config.decayPerDay)
            if (config.hitBonus < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("hitBonus_negative", config.hitBonus)
            if (config.baseImportance <= 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("baseImportance_non_positive", config.baseImportance)
            if (config.compileThreshold < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("compileThreshold_negative", config.compileThreshold)
            if (config.forgetSpeed < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("forgetSpeed_negative", config.forgetSpeed)
            if (config.compileThreshold >= config.baseImportance) {
                return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("threshold_ge_base", config.compileThreshold, config.baseImportance)
            }
            return null
        }

        /** 判断某条 fact 的当前 score 是否高到足以进入 memory.md。 */
        fun shouldCompile(score: Float, config: MemoryConfig): Boolean = score >= config.compileThreshold
    }
}
