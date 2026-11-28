package io.zer0.muse.ui.agentbot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentDreamReport
import io.zer0.muse.data.agentbot.AgentDreamRunner
import io.zer0.muse.data.agentbot.AgentDreamScheduler
import io.zer0.muse.data.agentbot.AgentDreamStore
import io.zer0.muse.data.profile.ProfileShardEngine
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** 「关于你」页中使用的一条记忆事实。 */
data class AboutYouFact(
    val id: Long,
    val content: String,
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val importance: Int = 0,
    val createdAt: String? = null,
    val pinned: Boolean = false,
    /**
     * 这一行来自哪个库（助手 id + 作用域 + Space）。
     *
     * 合并展示后同一个 Long id 可能出现在不同库里，光凭 id 做删除/置顶会改错行，
     * 因此行身份必须一路传到动作处。
     */
    val scope: io.zer0.memory.runtime.MemoryScopeKey? = null,
    /** 来自共享全局库的行只读：写入属于那个库的拥有者，不在这里改。 */
    val readOnly: Boolean = false,
)

/** 按「基础画像 / 偏好习惯 / 长期置顶 / 近期关注」分组后的画像。 */
data class AboutYouProfile(
    val basics: List<AboutYouFact> = emptyList(),
    val preferences: List<AboutYouFact> = emptyList(),
    val pinned: List<AboutYouFact> = emptyList(),
    val recent: List<AboutYouFact> = emptyList(),
) {
    val isEmpty: Boolean
        get() = basics.isEmpty() && preferences.isEmpty() && pinned.isEmpty() && recent.isEmpty()
}

/** 纯函数映射，便于在 AgentBotViewModel 之外单独测试分组规则。 */
object AboutYouProfileMapper {
    private val basicCategories = setOf("identity", "basic", "profile", "relationship")
    private val preferenceCategories = setOf("preference", "habit", "goal")

    fun map(facts: List<AboutYouFact>, limitPerSection: Int = 12): AboutYouProfile {
        val sorted = facts.sortedWith(
            compareByDescending<AboutYouFact> { it.importance }
                .thenByDescending { it.createdAt.orEmpty() },
        )
        val pinned = sorted.filter { it.pinned }.take(limitPerSection)
        val unpinned = sorted.filterNot { it.pinned }
        return AboutYouProfile(
            basics = unpinned.filter { it.category.lowercase() in basicCategories }.take(limitPerSection),
            preferences = unpinned.filter { it.category.lowercase() in preferenceCategories }.take(limitPerSection),
            pinned = pinned,
            recent = unpinned.filter {
                it.category.lowercase() !in basicCategories && it.category.lowercase() !in preferenceCategories
            }.take(limitPerSection),
        )
    }
}

/**
 * 工作台「关于你」Tab：展示记忆画像并允许删除、置顶、回到对话中修正。
 *
 * 同时承载 Dream 夜间整理的开关、上次整理摘要与「立即整理」试跑入口。
 * Dream 状态默认从本地 [AgentDreamStore] 读取；上层也可以显式传入覆盖值，
 * 便于后续把状态提升到 ViewModel 时保持同一套 UI。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkbenchAboutYouTab(
    profile: AboutYouProfile,
    onDelete: (AboutYouFact) -> Unit,
    onTogglePin: (AboutYouFact) -> Unit,
    onCorrectFact: (String) -> Unit,
    modifier: Modifier = Modifier,
    aboutYouEnabled: Boolean = true,
    onToggleAboutYou: ((Boolean) -> Unit)? = null,
    onDeleteDomain: ((ProfileShardEngine.ProfileDomain) -> Unit)? = null,
    dreamEnabled: Boolean? = null,
    dreamRunning: Boolean? = null,
    dreamReport: AgentDreamReport? = null,
    onToggleDream: ((Boolean) -> Unit)? = null,
    onRunDreamNow: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val dreamStore = remember(context) { AgentDreamStore(context) }
    val coroutineScope = rememberCoroutineScope()
    var localEnabled by remember(dreamStore) { mutableStateOf(dreamStore.isEnabled()) }
    var localRunning by remember { mutableStateOf(false) }
    var localReport by remember(dreamStore) { mutableStateOf(dreamStore.lastReport()) }
    var pendingDeleteDomain by remember { mutableStateOf<ProfileShardEngine.ProfileDomain?>(null) }

    val distinctFacts = remember(profile) {
        (profile.pinned + profile.basics + profile.preferences + profile.recent)
            .distinctBy { it.id to it.scope }
    }
    val domainGroups = remember(distinctFacts) {
        distinctFacts.groupBy { fact ->
            ProfileShardEngine.classifyFact(fact.content, fact.category, fact.tags)
        }
    }

    // 首次打开「关于你」时补一次注册：WorkManager 会持久化任务，重复注册由 KEEP 去重。
    LaunchedEffect(Unit) {
        if (dreamEnabled == null && localEnabled) {
            AgentDreamScheduler.scheduleNightly(context)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
    ) {
        if (onToggleAboutYou != null) {
            AboutYouToggleSection(
                enabled = aboutYouEnabled,
                onToggle = onToggleAboutYou,
            )
        }

        AboutYouDreamSection(
            context = context,
            dreamStore = dreamStore,
            coroutineScope = coroutineScope,
            enabled = dreamEnabled ?: localEnabled,
            running = dreamRunning ?: localRunning,
            report = dreamReport ?: localReport,
            actions = AboutYouDreamActions(
                onToggleDream = onToggleDream,
                onRunDreamNow = onRunDreamNow,
                onLocalEnabledChange = { localEnabled = it },
                onLocalRunningChange = { localRunning = it },
                onLocalReportChange = { localReport = it },
            ),
        )

        if (profile.isEmpty) {
            Text(
                text = stringResource(R.string.agent_about_you_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MusePaddings.contentGap),
            )
            return@Column
        }

        AboutYouDomainSections(
            domainGroups = domainGroups,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
            onCorrectFact = onCorrectFact,
            onRequestDeleteDomain = { pendingDeleteDomain = it },
        )
    }

    pendingDeleteDomain?.let { targetDomain ->
        val domainTitle = stringResource(domainLabelRes(targetDomain))
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_profile_domain_delete_confirm_title, domainTitle),
            itemName = domainTitle,
            consequence = stringResource(R.string.agent_profile_domain_delete_confirm_msg, domainTitle),
            onConfirm = {
                pendingDeleteDomain = null
                if (onDeleteDomain != null) {
                    onDeleteDomain(targetDomain)
                } else {
                    domainGroups[targetDomain].orEmpty()
                        .filterNot { it.readOnly }
                        .forEach(onDelete)
                }
            },
            onDismiss = { pendingDeleteDomain = null },
        )
    }
}

@Composable
private fun AboutYouDreamSection(
    context: android.content.Context,
    dreamStore: AgentDreamStore,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    enabled: Boolean,
    running: Boolean,
    report: AgentDreamReport?,
    actions: AboutYouDreamActions,
) {
    AgentDreamSection(
        enabled = enabled,
        running = running,
        report = report,
        onToggle = { checked ->
            if (actions.onToggleDream != null) {
                actions.onToggleDream(checked)
            } else {
                dreamStore.setEnabled(checked)
                actions.onLocalEnabledChange(checked)
                if (checked) {
                    AgentDreamScheduler.scheduleNightly(context)
                } else {
                    AgentDreamScheduler.cancelNightly(context)
                }
            }
        },
        onRunNow = {
            if (actions.onRunDreamNow != null) {
                actions.onRunDreamNow()
            } else if (!running) {
                actions.onLocalRunningChange(true)
                coroutineScope.launch {
                    try {
                        AgentDreamRunner.runNow(context)?.let(actions.onLocalReportChange)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        Logger.w("WorkbenchAboutYou", "dream run failed: " + e.message, e)
                    } finally {
                        actions.onLocalRunningChange(false)
                    }
                }
            }
        },
    )
}

private data class AboutYouDreamActions(
    val onToggleDream: ((Boolean) -> Unit)?,
    val onRunDreamNow: (() -> Unit)?,
    val onLocalEnabledChange: (Boolean) -> Unit,
    val onLocalRunningChange: (Boolean) -> Unit,
    val onLocalReportChange: (AgentDreamReport) -> Unit,
)

@Composable
private fun AboutYouDomainSections(
    domainGroups: Map<ProfileShardEngine.ProfileDomain, List<AboutYouFact>>,
    onDelete: (AboutYouFact) -> Unit,
    onTogglePin: (AboutYouFact) -> Unit,
    onCorrectFact: (String) -> Unit,
    onRequestDeleteDomain: (ProfileShardEngine.ProfileDomain) -> Unit,
) {
    val coreFacts = domainGroups[ProfileShardEngine.ProfileDomain.CORE].orEmpty()
    if (coreFacts.isNotEmpty()) {
        ProfileDomainSection(
            domain = ProfileShardEngine.ProfileDomain.CORE,
            facts = coreFacts,
            defaultExpanded = true,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
            onCorrectFact = onCorrectFact,
            onRequestDeleteDomain = { onRequestDeleteDomain(ProfileShardEngine.ProfileDomain.CORE) },
        )
    }
    ProfileShardEngine.ProfileDomain.SHARD_DOMAINS.forEach { domain ->
        val domainFacts = domainGroups[domain].orEmpty()
        if (domainFacts.isNotEmpty()) {
            ProfileDomainSection(
                domain = domain,
                facts = domainFacts,
                defaultExpanded = false,
                onDelete = onDelete,
                onTogglePin = onTogglePin,
                onCorrectFact = onCorrectFact,
                onRequestDeleteDomain = { onRequestDeleteDomain(domain) },
            )
        }
    }
}

/** 「关于你」独立开关卡：控制核心卡与分域知识库按需注入，与 Dream 夜间整理彼此独立。 */
@Composable
private fun AboutYouToggleSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = stringResource(R.string.agent_about_you_enable_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.agent_about_you_enable_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MuseSwitch(checked = enabled, onCheckedChange = onToggle)
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.agent_about_you_disabled_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Dream 夜间整理卡片：开关、上次摘要与立即试跑。 */
@Composable
private fun AgentDreamSection(
    enabled: Boolean,
    running: Boolean,
    report: AgentDreamReport?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(text = stringResource(R.string.memory_dream_title))
        Surface(
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Text(
                            text = stringResource(R.string.memory_organize_action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.memory_dream_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseSwitch(checked = enabled, onCheckedChange = onToggle)
                }
                if (report != null) {
                    Text(
                        text = stringResource(
                            R.string.memory_screen_last_success,
                            formatDreamTime(report.generatedAt),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (report.morningSummary.isNotBlank()) {
                        Text(
                            text = report.morningSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val handledCount = report.archivedTemporaryCount + report.mergedPreferenceCount
                    Text(
                        text = if (handledCount > 0) {
                            stringResource(R.string.memory_organize_stage_complete, handledCount)
                        } else {
                            stringResource(R.string.memory_organize_stage_no_duplicates)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.memory_dream_start),
                    onClick = onRunNow,
                    enabled = !running,
                    loading = running,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                    leadingIcon = MuseIcons.sparkle,
                )
            }
        }
    }
}

/** 本地时间格式化；报告时间戳来自系统时钟，不涉及用户可读文案。 */
private fun formatDreamTime(epochMillis: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return formatter.format(Date(epochMillis))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDomainSection(
    domain: ProfileShardEngine.ProfileDomain,
    facts: List<AboutYouFact>,
    defaultExpanded: Boolean,
    onDelete: (AboutYouFact) -> Unit,
    onTogglePin: (AboutYouFact) -> Unit,
    onCorrectFact: (String) -> Unit,
    onRequestDeleteDomain: () -> Unit,
) {
    if (facts.isEmpty()) return
    var expanded by rememberSaveable(domain.key) { mutableStateOf(defaultExpanded) }
    val hasWritable = facts.any { !it.readOnly }
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            ProfileDomainHeader(
                domain = domain,
                factCount = facts.size,
                expanded = expanded,
                hasWritable = hasWritable,
                onToggleExpanded = { expanded = !expanded },
                onRequestDeleteDomain = onRequestDeleteDomain,
            )
            if (!expanded && domain != ProfileShardEngine.ProfileDomain.CORE) {
                Text(
                    text = stringResource(R.string.agent_profile_domain_collapsed_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                DomainFactList(
                    facts = facts,
                    onDelete = onDelete,
                    onTogglePin = onTogglePin,
                    onCorrectFact = onCorrectFact,
                )
            }
        }
    }
}

@Composable
private fun ProfileDomainHeader(
    domain: ProfileShardEngine.ProfileDomain,
    factCount: Int,
    expanded: Boolean,
    hasWritable: Boolean,
    onToggleExpanded: () -> Unit,
    onRequestDeleteDomain: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        Text(
            text = stringResource(domainLabelRes(domain)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = MuseShapes.pill,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = stringResource(R.string.agent_profile_domain_count, factCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xxs),
            )
        }
        if (hasWritable) {
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = onRequestDeleteDomain,
                contentDescription = stringResource(R.string.agent_profile_domain_delete_cd),
                size = MuseIconSizes.controlTouch,
                iconSize = MuseIconSizes.iconSmall,
            )
        }
        MuseTactileButton(
            icon = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            onClick = onToggleExpanded,
            contentDescription = stringResource(
                if (expanded) R.string.agent_space_collapse_cd else R.string.agent_space_expand_cd,
            ),
            size = MuseIconSizes.controlTouch,
            iconSize = MuseIconSizes.iconSmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DomainFactList(
    facts: List<AboutYouFact>,
    onDelete: (AboutYouFact) -> Unit,
    onTogglePin: (AboutYouFact) -> Unit,
    onCorrectFact: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        facts.forEach { fact ->
            DomainFactCard(
                fact = fact,
                onDelete = onDelete,
                onTogglePin = onTogglePin,
                onCorrectFact = onCorrectFact,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DomainFactCard(
    fact: AboutYouFact,
    onDelete: (AboutYouFact) -> Unit,
    onTogglePin: (AboutYouFact) -> Unit,
    onCorrectFact: (String) -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Text(
                text = fact.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (fact.tags.isNotEmpty()) {
                Text(
                    text = fact.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                val readOnly = fact.readOnly
                MuseCapsuleButton(
                    text = stringResource(
                        if (fact.pinned) R.string.agent_about_you_unpin else R.string.agent_about_you_pin,
                    ),
                    onClick = { onTogglePin(fact) },
                    enabled = !readOnly,
                    leadingIcon = MuseIcons.pin,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_about_you_correct),
                    onClick = { onCorrectFact(fact.content) },
                    enabled = !readOnly,
                    leadingIcon = MuseIcons.edit,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.agent_about_you_delete),
                    onClick = { onDelete(fact) },
                    enabled = !readOnly,
                    leadingIcon = MuseIcons.trash,
                    destructive = true,
                    fillWidth = false,
                    size = MuseButtonSize.Compact,
                )
            }
        }
    }
}

private fun domainLabelRes(domain: ProfileShardEngine.ProfileDomain): Int = when (domain) {
    ProfileShardEngine.ProfileDomain.CORE -> R.string.agent_profile_domain_core
    ProfileShardEngine.ProfileDomain.HABITS -> R.string.agent_profile_domain_habits
    ProfileShardEngine.ProfileDomain.HEALTH -> R.string.agent_profile_domain_health
    ProfileShardEngine.ProfileDomain.INTERPERSONAL -> R.string.agent_profile_domain_interpersonal
    ProfileShardEngine.ProfileDomain.WORK_STUDY -> R.string.agent_profile_domain_work_study
    ProfileShardEngine.ProfileDomain.PREFERENCES -> R.string.agent_profile_domain_preferences
}
