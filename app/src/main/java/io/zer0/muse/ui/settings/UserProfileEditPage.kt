package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.ui.components.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.133: 重写用户画像编辑页 — 所有字段都注入 system prompt,模型真正"知道"用户。
 *
 * 设计目标:
 *  - 5 个分组:称呼 / 基本信息 / 背景与专长 / 沟通偏好 / 边界与忌讳
 *  - 每个字段加说明文字,让用户明白填什么、对 AI 的影响
 *  - 顶部加 InfoCard 解释"填写的信息会注入到 AI 的 system prompt,让 AI 更了解你"
 *  - 即时保存模式(类似原实现):每次输入变更用 scope.launch 写回 DataStore
 *
 * 字段映射(SystemPromptAssembler.buildUserProfileSection):
 *  - 称呼组:userNickName / assistantName
 *  - 基本信息:bio / age / city / timezone
 *  - 背景与专长:occupation / educationBackground / professionField / skills / interests
 *  - 沟通偏好:communicationStyle / responseLength / preferredTone / preferredLanguage
 *  - 边界与忌讳:avoidTopics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileEditPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // 收集当前用户画像,跟随 DataStore 自动更新
    val profile by produceState<UserProfile>(initialValue = UserProfile()) {
        settings.userProfileFlow.collect { value = it }
    }

    // 即时保存:读取 DB 最新值,应用 transform 后写回(避免并发覆盖)
    fun update(transform: (UserProfile) -> UserProfile) {
        scope.launch {
            settings.saveUserProfile(transform(settings.getUserProfile()))
        }
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
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
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
                        OutlinedTextField(
                            value = profile.userNickName ?: "",
                            onValueChange = { v -> update { it.copy(userNickName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_assistant_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_assistant_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_assistant_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.assistantName ?: "",
                            onValueChange = { v -> update { it.copy(assistantName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_your_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_your_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_your_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
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
                        OutlinedTextField(
                            value = profile.bio ?: "",
                            onValueChange = { v -> update { it.copy(bio = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_bio)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_bio_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_bio_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = MuseShapes.medium,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.age ?: "",
                            onValueChange = { v -> update { it.copy(age = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_age)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.city ?: "",
                            onValueChange = { v -> update { it.copy(city = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_city)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 时区
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.timezone ?: "",
                            onValueChange = { v -> update { it.copy(timezone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_timezone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_timezone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
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
                        OutlinedTextField(
                            value = profile.occupation ?: "",
                            onValueChange = { v -> update { it.copy(occupation = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_occupation)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 教育背景
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.educationBackground ?: "",
                            onValueChange = { v -> update { it.copy(educationBackground = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_education)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_education_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.professionField ?: "",
                            onValueChange = { v -> update { it.copy(professionField = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_profession_field)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_profession_field_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 技能专长
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.skills ?: "",
                            onValueChange = { v -> update { it.copy(skills = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_skills)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_skills_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.interests ?: "",
                            onValueChange = { v -> update { it.copy(interests = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_interests_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = MuseShapes.medium,
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
                        OutlinedTextField(
                            value = profile.communicationStyle ?: "",
                            onValueChange = { v -> update { it.copy(communicationStyle = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_comm_style)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_comm_style_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 回复长度偏好
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.responseLength ?: "",
                            onValueChange = { v -> update { it.copy(responseLength = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_resp_length)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_resp_length_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 偏好语气
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.preferredTone ?: "",
                            onValueChange = { v -> update { it.copy(preferredTone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_tone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_tone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
                        )
                    },
                )
                // v1.133: 偏好回复语言
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = profile.preferredLanguage ?: "",
                            onValueChange = { v -> update { it.copy(preferredLanguage = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_lang)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_lang_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MuseShapes.medium,
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
                        OutlinedTextField(
                            value = profile.avoidTopics ?: "",
                            onValueChange = { v -> update { it.copy(avoidTopics = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_avoid_topics)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_avoid_topics_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_avoid_topics_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = MuseShapes.medium,
                        )
                    },
                )
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(MusePaddings.screen)) }
    }
}
