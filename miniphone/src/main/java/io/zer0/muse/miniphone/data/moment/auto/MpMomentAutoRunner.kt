package io.zer0.muse.miniphone.data.moment.auto

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import kotlin.random.Random

/** 一轮后台生成的结果:发成了、跳过了、还是失败了。日志里能看到的判断,这里也能断言。 */
sealed interface MpMomentAutoOutcome {
    /** 落库成功。 */
    data class Published(val momentId: String, val characterId: String) : MpMomentAutoOutcome

    /** 判定为不发,原因见 [reason]。 */
    data class Skipped(val reason: MpMomentSkipReason) : MpMomentAutoOutcome

    /** 判定为要发,但生成或落库失败;[reason] 是写进日志的取值。 */
    data class Failed(val reason: String) : MpMomentAutoOutcome
}

/**
 * 「让角色自己发一条动态」的一轮完整流程。
 *
 * 顺序与 [MpMomentAutoPlan.decide] 一致:先过四道跳过判定,判完才挑角色、调宿主生成、落库。
 * 失败**不重试** —— WorkManager 下个周期自然会再来,一轮里重试只会把一次配额消耗变成三次。
 *
 * 时间、时区、随机源都由调用方交进来(默认取系统值),于是这套流程能在测试里完整跑一遍:
 * 「该不该发」交给纯函数判,「发了之后库里多了什么」用真 Room 断言。
 *
 * 生成素材由 [material] 拼:最近聊过什么、上一条发过什么、现在几点,都不在本类里查。
 *
 * @param now 时间源,默认系统时钟
 * @param random 随机源:一次概率骰子 + 挑哪个角色,测试可钉死
 * @param zone 判定「现在几点」用的时区
 */
class MpMomentAutoRunner(
    private val host: MiniPhoneHost,
    private val repository: MpMomentsRepository,
    private val store: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** 跑一轮。可失败但**不抛异常**:所有异常都归一成 [MpMomentAutoOutcome]。 */
    suspend fun run(): MpMomentAutoOutcome {
        val at = now()
        val enabled = resultOf { store.isEnabled() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
            ?: return MpMomentAutoOutcome.Failed(FAIL_SETTINGS)
        val lastGeneratedAt = resultOf { store.lastGeneratedAt() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
        val characters = resultOf { host.observeCharacters().first() }
            .onError { msg, t -> fail("characters_unreadable", t, msg) }
            .getOrNull()
            .orEmpty()
        val decision = MpMomentAutoPlan.decide(
            nowMillis = at,
            lastGeneratedAtMillis = lastGeneratedAt,
            roll = random.nextDouble(),
            hasCharacter = characters.isNotEmpty(),
            enabled = enabled,
            zone = zone,
        )
        return when (decision) {
            is MpMomentAutoDecision.Skip -> skip(decision.reason)
            MpMomentAutoDecision.Generate -> publish(characters, at)
        }
    }

    /** 判定为不发:留一条能看出原因的日志,然后结束。 */
    private fun skip(reason: MpMomentSkipReason): MpMomentAutoOutcome {
        Logger.d(TAG, "朋友圈定时跳过 | reason=${reason.value}")
        return MpMomentAutoOutcome.Skipped(reason)
    }

    /** 挑角色 → 拼素材 → 让宿主生成 → 落库 → 记下这次的时间。任一步失败就收手。 */
    private suspend fun publish(characters: List<MpCharacter>, at: Long): MpMomentAutoOutcome {
        val character: MpCharacter = characters.random(random)
        val focus = MpMomentAutoPlan.hintFor(MpMomentAutoPlan.hourOf(at, zone))
        val hint = material.forMoment(character.id, focus)
        Logger.d(TAG, "朋友圈定时生成 | characterId=${character.id} | focus=$focus")
        val content = resultOf { host.composeMoment(character.id, hint) }
            .onError { msg, t -> fail("compose_error", t, msg) }
            .getOrNull()
        if (content.isNullOrBlank()) {
            // 宿主已经把失败吞成 null 了;这里再记一笔「这一轮没结果」就收手,不重试
            Logger.w(TAG, "朋友圈定时失败 | reason=compose_null")
            return MpMomentAutoOutcome.Failed(FAIL_COMPOSE)
        }
        val moment = resultOf { repository.insertFromCharacter(character, content) }
            .onError { msg, t -> fail("insert_error", t, msg) }
            .getOrNull()
        if (moment == null) {
            Logger.w(TAG, "朋友圈定时失败 | reason=empty_content")
            return MpMomentAutoOutcome.Failed(FAIL_INSERT)
        }
        // 时间戳写失败不影响已经落库的动态:最多让下一轮少一次防抖保护
        resultOf { store.markGenerated(at) }
            .onError { msg, t -> fail("settings_write", t, msg) }
        Logger.d(TAG, "朋友圈定时落库 | momentId=${moment.id} | len=${moment.content.length}")
        return MpMomentAutoOutcome.Published(momentId = moment.id, characterId = character.id)
    }

    private fun fail(reason: String, throwable: Throwable?, message: String) {
        Logger.w(TAG, "朋友圈定时失败 | reason=$reason | ${throwable?.message ?: message}", throwable)
    }

    private companion object {
        const val TAG: String = "MiniPhoneMoment"

        const val FAIL_SETTINGS: String = "settings_unreadable"

        const val FAIL_COMPOSE: String = "compose_null"

        const val FAIL_INSERT: String = "insert_skipped"
    }
}
