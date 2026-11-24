package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 用户资料页的防抖落盘器。
 *
 * UI 仍由页面内的本地 mutableStateOf 同步驱动，避免 IME composing 被打断；
 * 这里负责把最新 profile 在停顿 500ms 后写入 DataStore，并在页面销毁时兜底保存。
 */
private class UserProfileSaveSink(private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + GlobalCoroutineExceptionHandler)
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
            CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler).launch {
                settings.saveUserProfile(value)
            }
        }
        scope.cancel()
    }
}

@Composable
fun UserProfileEditPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val saveSink = remember { UserProfileSaveSink(settings) }

    var profile by remember { mutableStateOf(UserProfile()) }
    LaunchedEffect(Unit) {
        settings.userProfileFlow.collect { incoming ->
            if (incoming != profile) profile = incoming
        }
    }

    DisposableEffect(Unit) {
        onDispose { saveSink.dispose() }
    }

    fun update(transform: (UserProfile) -> UserProfile) {
        val next = transform(profile)
        profile = next
        saveSink.schedule(next)
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_user_profile_title), onBack = onBack) {
        item(key = "profile_appellation") {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_appellation)) }) {
                content(key = "appellation_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        MuseTextField(
                            value = profile.assistantName ?: "",
                            onValueChange = { v -> update { it.copy(assistantName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_assistant_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_assistant_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_assistant_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.userNickName ?: "",
                            onValueChange = { v -> update { it.copy(userNickName = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_your_name)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_your_name_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_your_name_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "profile_basic_info") {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_basic_info)) }) {
                content(key = "basic_info_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        MuseTextField(
                            value = profile.bio ?: "",
                            onValueChange = { v -> update { it.copy(bio = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_bio)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_bio_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_bio_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        MuseTextField(
                            value = profile.age ?: "",
                            onValueChange = { v -> update { it.copy(age = v.filter { c -> c.isDigit() }.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_age)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.city ?: "",
                            onValueChange = { v -> update { it.copy(city = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_city)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.timezone ?: "",
                            onValueChange = { v -> update { it.copy(timezone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_timezone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_timezone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "profile_background") {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_background)) }) {
                content(key = "background_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        MuseTextField(
                            value = profile.occupation ?: "",
                            onValueChange = { v -> update { it.copy(occupation = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_occupation)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.educationBackground ?: "",
                            onValueChange = { v -> update { it.copy(educationBackground = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_education)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_education_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.professionField ?: "",
                            onValueChange = { v -> update { it.copy(professionField = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_profession_field)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_profession_field_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.skills ?: "",
                            onValueChange = { v -> update { it.copy(skills = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_skills)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_skills_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.interests ?: "",
                            onValueChange = { v -> update { it.copy(interests = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_interests_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                }
            }
        }

        item(key = "profile_communication") {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_communication)) }) {
                content(key = "communication_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        MuseTextField(
                            value = profile.communicationStyle ?: "",
                            onValueChange = { v -> update { it.copy(communicationStyle = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_comm_style)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_comm_style_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.responseLength ?: "",
                            onValueChange = { v -> update { it.copy(responseLength = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_resp_length)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_resp_length_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.preferredTone ?: "",
                            onValueChange = { v -> update { it.copy(preferredTone = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_tone)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_tone_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MuseTextField(
                            value = profile.preferredLanguage ?: "",
                            onValueChange = { v -> update { it.copy(preferredLanguage = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_pref_lang)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_pref_lang_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "profile_boundaries") {
            CardGroup(title = { Text(stringResource(R.string.settings_user_profile_boundaries)) }) {
                content(key = "boundaries_fields") {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        MuseTextField(
                            value = profile.avoidTopics ?: "",
                            onValueChange = { v -> update { it.copy(avoidTopics = v.ifBlank { null }) } },
                            label = { Text(stringResource(R.string.settings_user_profile_avoid_topics)) },
                            placeholder = { Text(stringResource(R.string.settings_user_profile_avoid_topics_hint)) },
                            supportingText = { Text(stringResource(R.string.settings_user_profile_avoid_topics_desc)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                }
            }
        }

        item(key = "profile_snapshots") { ProfileSnapshotSection() }

        item(key = "profile_spacer") { Spacer(Modifier.height(MusePaddings.screen)) }
    }
}
