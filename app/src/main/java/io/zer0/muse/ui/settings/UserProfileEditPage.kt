package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * v1.133: 重写用户画像编辑页 — 所有字段都注入 system prompt,模型真正"知道"用户。
 *
 * 设计目标:
 *  - 5 个分组:称呼 / 基本信息 / 背景与专长 / 沟通偏好 / 边界与忌讳
 *  - 每个字段加说明文字,让用户明白填什么、对 AI 的影响
 *  - 顶部加 InfoCard 解释"填写的信息会注入到 AI 的 system prompt,让 AI 更了解你"
 *  - B7-09 防抖保存:本地 state 同步更新,停顿 500ms 后写回 DataStore,离开页面时兜底保存
 *
 * 字段映射(SystemPromptAssembler.buildUserProfileSection):
 *  - 称呼组:userNickName / assistantName
 *  - 基本信息:bio / age / city / timezone
 *  - 背景与专长:occupation / educationBackground / professionField / skills / interests
 *  - 沟通偏好:communicationStyle / responseLength / preferredTone / preferredLanguage
 *  - 边界与忌讳:avoidTopics
 */

/**
 * B7-09: 用户资料页的防抖落盘器。
 *
 * UI 仍由页面内的本地 mutableStateOf 同步驱动,避免 IME composing 被打断;
 * 这里只负责把最新 profile 在停顿 500ms 后写入 DataStore,并在页面销毁时兜底保存。
 */
private class UserProfileSaveSink(
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var latest: UserProfile = UserProfile()

    fun schedule(value: UserProfile) {
        latest = value
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            settings.saveUserProfile(value)
        }
    }

    fun dispose() {
        saveJob?.cancel()
        val value = latest
        if (value != UserProfile()) {
            // 页面已离开,不能用页面级 scope;用独立 IO scope 做最后一次兜底写入
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                settings.saveUserProfile(value)
            }
        }
        scope.cancel()
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val saveSink = remember { UserProfileSaveSink(settings) }

    // v1.0.27 修复 IME bug: 用本地 mutableStateOf 同步持有 profile,避免 produceState + scope.launch
    // 的异步往返破坏 IME composing text (用户反馈"打逗号时光标跳到逗号前面")。
    // 旧实现:profile 从 userProfileFlow 异步收集 → update 启动协程读 DB → transform → 写 DB →
    //        flow emit → produceState 更新 → TextField value 变化 → IME composing 错乱。
    // 新实现:本地 state 同步更新 → 异步写 DB;外部 flow 变化时同步到本地(若内容确实不同)。
    var profile by remember { mutableStateOf(UserProfile()) }
    LaunchedEffect(Unit) {
        settings.userProfileFlow.collect { incoming ->
            // 仅当 DB 值与本地不一致时同步(避免写回后 echo 导致重组打断 IME)
            if (incoming != profile) profile = incoming
        }
    }

    DisposableEffect(Unit) {
        onDispose { saveSink.dispose() }
    }

    // 同步更新本地 state + 防抖写回 DataStore
    fun update(transform: (UserProfile) -> UserProfile) {
        val next = transform(profile)
        profile = next // 同步更新,IME 立即看到新 value
        saveSink.schedule(next)
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_user_profile_title), onBack = onBack) {
        // 顶部 InfoCard:说明这些信息会注入到 AI 的 system prompt
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = TablerIcons.InfoCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.settings_user_profile_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── 分组 1: 称呼设置(最高优先级,影响 AI 自称与对用户的称呼)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_appellation)) }) {
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.assistantName ?: "",
                            onValueChange = { v -> update { it.copy(assistantName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_assistant_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_assistant_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_assistant_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.userNickName ?: "",
                            onValueChange = { v -> update { it.copy(userNickName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_your_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_your_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_your_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── 分组 2: 基本信息(让 AI 对用户有整体认知)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_basic_info)) }) {
                // v1.133: 个人简介(长文本,一段话自由介绍)
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.bio ?: "",
                            onValueChange = { v -> update { it.copy(bio = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_bio)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_bio_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_bio_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.age ?: "",
                            onValueChange = { v -> update { it.copy(age = v.filter { c -> c.isDigit() }.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_age)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.city ?: "",
                            onValueChange = { v -> update { it.copy(city = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_city)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 时区
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.timezone ?: "",
                            onValueChange = { v -> update { it.copy(timezone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_timezone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_timezone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── 分组 3: 背景与专长 ──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_background)) }) {
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.occupation ?: "",
                            onValueChange = { v -> update { it.copy(occupation = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_occupation)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 教育背景
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.educationBackground ?: "",
                            onValueChange = { v -> update { it.copy(educationBackground = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_education)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_education_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.professionField ?: "",
                            onValueChange = { v -> update { it.copy(professionField = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_profession_field)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_profession_field_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 技能专长
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.skills ?: "",
                            onValueChange = { v -> update { it.copy(skills = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_skills)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_skills_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.interests ?: "",
                            onValueChange = { v -> update { it.copy(interests = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_interests_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
            }
        }

        // ── 分组 4: 沟通偏好(影响 AI 回复风格)──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_communication)) }) {
                // v1.133: 沟通风格
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.communicationStyle ?: "",
                            onValueChange = { v -> update { it.copy(communicationStyle = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_comm_style)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_comm_style_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 回复长度偏好
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.responseLength ?: "",
                            onValueChange = { v -> update { it.copy(responseLength = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_resp_length)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_resp_length_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 偏好语气
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.preferredTone ?: "",
                            onValueChange = { v -> update { it.copy(preferredTone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_tone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_tone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
                // v1.133: 偏好回复语言
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.preferredLanguage ?: "",
                            onValueChange = { v -> update { it.copy(preferredLanguage = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_lang)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_lang_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        // ── 分组 5: 边界与忌讳 ──
        item {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_boundaries)) }) {
                // v1.133: 忌讳话题
                item(
                    headlineContent = {
                        MuseTextField(
                            value = profile.avoidTopics ?: "",
                            onValueChange = { v -> update { it.copy(avoidTopics = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_avoid_topics)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_avoid_topics_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_avoid_topics_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    },
                )
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(MusePaddings.screen)) }
    }
}
