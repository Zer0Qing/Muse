@file:Suppress("LongMethod", "LongParameterList")

package io.zer0.muse.ui.agentbot

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Date
import java.text.DateFormat as JavaDateFormat

/** 一个零配置模板生成的卡片草稿；ViewModel 只消费字段，不关心模板如何拼装。 */
/**
 * 工作台「小应用」页。
 *
 * 已钉入卡片按创建顺序展示，右上角圆环表示当前刷新周期的进度，点圆环立即刷新；
 * 时钟菜单调整自动刷新频率；下方提供四个零配置模板，一键生成即可使用。
 * 外层 [io.zer0.muse.ui.common.form.MuseBottomSheet] 已带滚动，本页不再嵌套无界滚动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkbenchSpacesTab(
    cards: List<CardEntity>,
    refreshingIds: Set<String>,
    refreshFailures: Map<String, io.zer0.muse.data.agentbot.SpaceRefreshFailure> = emptyMap(),
    onRefresh: (String) -> Unit,
    onSetRefreshPolicy: (cardId: String, intervalMinutes: Int, refreshPrompt: String) -> Unit,
    onUnpin: (String) -> Unit,
    onRefineInChat: (cardId: String, title: String) -> Unit,
    onCreateTemplate: (SpaceTemplateDraft) -> Unit,
    onCardAction: (cardId: String, CardAction) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val templates = remember(context.resources, configuration) {
        SpaceTemplateFactory.build(context.resources)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
    ) {
        if (cards.isEmpty()) {
            MuseEmptyState(
                icon = MuseIcons.template,
                title = stringResource(R.string.agent_space_empty_title),
                subtitle = stringResource(R.string.agent_space_empty_desc),
            )
        } else {
            SectionLabel(text = stringResource(R.string.agent_space_pinned_title))
            cards.forEach { card ->
                SpaceCardItem(
                    card = card,
                    refreshing = card.id in refreshingIds,
                    refreshFailed = card.id in refreshFailures,
                    onRefresh = { onRefresh(card.id) },
                    onSetRefreshPolicy = { minutes -> onSetRefreshPolicy(card.id, minutes, card.refreshPrompt) },
                    onUnpin = { onUnpin(card.id) },
                    onRefineInChat = { onRefineInChat(card.id, card.title) },
                    onCardAction = { action -> onCardAction(card.id, action) },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            SectionLabel(text = stringResource(R.string.agent_space_templates_title))
            Text(
                text = stringResource(R.string.agent_space_templates_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                templates.forEach { template ->
                    SpaceTemplateChip(
                        template = template,
                        onClick = { onCreateTemplate(template) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceCardItem(
    card: CardEntity,
    refreshing: Boolean,
    refreshFailed: Boolean,
    onRefresh: () -> Unit,
    onSetRefreshPolicy: (Int) -> Unit,
    onUnpin: () -> Unit,
    onRefineInChat: () -> Unit,
    onCardAction: (CardAction) -> Unit,
) {
    var cardExpanded by rememberSaveable(card.id) { mutableStateOf(true) }
    var refreshMenuOpen by rememberSaveable(card.id) { mutableStateOf(false) }
    var nowMillis by remember(card.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(card.id, card.lastRefreshedAt, card.refreshIntervalMinutes) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(SPACE_REFRESH_RING_TICK_MS)
        }
    }
    val lastRefreshedText = if (card.lastRefreshedAt > 0L) {
        stringResource(R.string.agent_space_last_refreshed, formatSpaceTime(card.lastRefreshedAt))
    } else {
        stringResource(R.string.agent_space_never_refreshed)
    }
    val intervalText = refreshIntervalLabel(card.refreshIntervalMinutes)

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title.ifBlank { card.id },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = intervalText + " · " + lastRefreshedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SpaceRefreshRing(
                    refreshing = refreshing,
                    progress = spaceRefreshProgress(card, nowMillis),
                    onClick = onRefresh,
                )
                Box {
                    MuseTactileButton(
                        icon = MuseIcons.clock,
                        onClick = { refreshMenuOpen = true },
                        contentDescription = stringResource(R.string.agent_space_refresh_menu_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = MuseIconSizes.controlTouch,
                        iconSize = MuseIconSizes.iconSmall,
                    )
                    MuseAnchoredMenu(
                        expanded = refreshMenuOpen,
                        onDismissRequest = { refreshMenuOpen = false },
                        alignEnd = true,
                    ) {
                        SPACE_REFRESH_OPTIONS.forEach { minutes ->
                            MuseListItem(
                                onClick = {
                                    refreshMenuOpen = false
                                    onSetRefreshPolicy(minutes)
                                },
                                headlineContent = {
                                    Text(
                                        text = refreshIntervalLabel(minutes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                            )
                        }
                    }
                }
                MuseTactileButton(
                    icon = if (cardExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    onClick = { cardExpanded = !cardExpanded },
                    contentDescription = stringResource(
                        if (cardExpanded) R.string.agent_space_collapse_cd else R.string.agent_space_expand_cd,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = onUnpin,
                    contentDescription = stringResource(R.string.agent_space_unpin_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = MuseIconSizes.controlTouch,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }

            if (refreshFailed) {
                Text(
                    text = stringResource(R.string.agent_space_refresh_failed_retrying),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.statusColors.error,
                )
            }

            AnimatedVisibility(visible = cardExpanded) {
                RichContentCard(
                    language = if (card.isSvg) "svg" else "html",
                    content = card.code,
                    modifier = Modifier.fillMaxWidth(),
                    showPreviewButton = true,
                    onCardAction = onCardAction,
                    cardId = card.id,
                    inlineCardData = card.dataJson ?: "{}",
                    onRefineInChat = onRefineInChat,
                )
            }
        }
    }
}

@Composable
private fun SpaceRefreshRing(refreshing: Boolean, progress: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(MuseIconSizes.touchTarget),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            MuseSpinner(
                size = MuseIconSizes.controlTouch,
                strokeWidth = MuseIconSizes.progressStroke,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.divider)
            val activeColor = MaterialTheme.colorScheme.primary
            val sweep = progress.coerceIn(0f, 1f) * 360f
            Canvas(modifier = Modifier.size(MuseIconSizes.controlTouch)) {
                val strokePx = MuseIconSizes.progressStroke.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
                if (sweep > 0f) {
                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke,
                    )
                }
            }
        }
        MuseTactileButton(
            icon = MuseIcons.refresh,
            onClick = onClick,
            contentDescription = stringResource(R.string.agent_space_refresh_now_cd),
            tint = MaterialTheme.colorScheme.primary,
            size = MuseIconSizes.touchTarget,
            iconSize = MuseIconSizes.iconTiny,
        )
    }
}

@Composable
private fun SpaceTemplateChip(template: SpaceTemplateDraft, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MuseAlpha.mutedSurface),
        border = BorderStroke(MuseBorderWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.inputPadding,
                vertical = MusePaddings.contentGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = templateIcon(template.id),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = template.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun templateIcon(id: String): ImageVector = when (id) {
    SpaceTemplateFactory.DAILY_ID -> MuseIcons.calendar
    SpaceTemplateFactory.HABITS_ID -> MuseIcons.flame
    SpaceTemplateFactory.TODO_ID -> MuseIcons.check
    SpaceTemplateFactory.FOCUS_ID -> MuseIcons.timer
    else -> MuseIcons.template
}

private val SPACE_REFRESH_OPTIONS = listOf(0, 30, 120, 1_440, 10_080)

private const val SPACE_REFRESH_RING_TICK_MS = 30_000L

private fun spaceRefreshProgress(card: CardEntity, now: Long): Float {
    if (card.refreshIntervalMinutes <= 0 || card.lastRefreshedAt <= 0L) return 0f
    val intervalMs = card.refreshIntervalMinutes.coerceAtMost(10_080) * 60_000f
    if (intervalMs <= 0f) return 0f
    return ((now - card.lastRefreshedAt).toFloat() / intervalMs).coerceIn(0f, 1f)
}

private fun formatSpaceTime(epochMillis: Long): String =
    JavaDateFormat.getDateTimeInstance(JavaDateFormat.SHORT, JavaDateFormat.SHORT).format(Date(epochMillis))

@Composable
private fun refreshIntervalLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.agent_space_refresh_off)
    minutes % 10_080 == 0 -> stringResource(R.string.agent_space_refresh_every_days, minutes / 1_440)
    minutes % 1_440 == 0 -> stringResource(R.string.agent_space_refresh_every_days, minutes / 1_440)
    minutes % 60 == 0 -> stringResource(R.string.agent_space_refresh_every_hours, minutes / 60)
    else -> stringResource(R.string.agent_space_refresh_every_minutes, minutes)
}

/**
 * 四个零配置模板：每个模板只依赖系统时间或卡片内本地状态，生成后无需任何配置即可使用。
 * 文案全部来自字符串资源，模板源码只保留结构与脚本。
 */
private object SpaceTemplateFactory {

    const val DAILY_ID = "daily_overview"
    const val HABITS_ID = "habit_tracker"
    const val TODO_ID = "todo_list"
    const val FOCUS_ID = "focus_timer"
    const val GOMOKU_ID = "gomoku_board"

    fun build(resources: Resources): List<SpaceTemplateDraft> = listOf(
        dailyOverview(resources),
        habitTracker(resources),
        todoList(resources),
        focusTimer(resources),
        gomokuArena(resources),
    )

    private fun dailyOverview(resources: Resources): SpaceTemplateDraft {
        val title = resources.getString(R.string.agent_space_template_daily_title)
        val description = resources.getString(R.string.agent_space_template_daily_desc)
        val today = htmlEscape(resources.getString(R.string.agent_space_template_daily_today))
        val week = htmlEscape(resources.getString(R.string.agent_space_template_daily_week))
        val month = htmlEscape(resources.getString(R.string.agent_space_template_daily_month))
        val html = """
            <div class="m-shell">
              <div class="m-eyebrow" id="daily-weekday"></div>
              <div class="m-title" id="daily-date"></div>
              <div class="m-metrics">
                <div class="m-metric"><div class="m-metric-value" id="daily-day">0%</div><div class="m-metric-label">$today</div></div>
                <div class="m-metric"><div class="m-metric-value" id="daily-week">0%</div><div class="m-metric-label">$week</div></div>
                <div class="m-metric"><div class="m-metric-value" id="daily-month">0%</div><div class="m-metric-label">$month</div></div>
              </div>
            </div>
            <script>
            (function(){
              function pct(value, total){ if (total <= 0) return 0; return Math.max(0, Math.min(100, Math.round(value * 100 / total))); }
              function render(){
                var now = new Date();
                try {
                  document.getElementById('daily-weekday').textContent = new Intl.DateTimeFormat(undefined, {weekday:'long'}).format(now);
                  document.getElementById('daily-date').textContent = new Intl.DateTimeFormat(undefined, {year:'numeric', month:'long', day:'numeric'}).format(now);
                } catch (e) {}
                var dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                var dayEnd = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
                var weekStart = new Date(now);
                var shift = (now.getDay() + 6) % 7;
                weekStart.setDate(now.getDate() - shift);
                weekStart.setHours(0, 0, 0, 0);
                var weekEnd = new Date(weekStart);
                weekEnd.setDate(weekStart.getDate() + 7);
                var monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
                var monthEnd = new Date(now.getFullYear(), now.getMonth() + 1, 1);
                document.getElementById('daily-day').textContent = pct(now - dayStart, dayEnd - dayStart) + '%';
                document.getElementById('daily-week').textContent = pct(now - weekStart, weekEnd - weekStart) + '%';
                document.getElementById('daily-month').textContent = pct(now - monthStart, monthEnd - monthStart) + '%';
              }
              render();
              setInterval(render, 60000);
            })();
            </script>
        """.trimIndent()
        return SpaceTemplateDraft(
            id = DAILY_ID,
            title = title,
            description = description,
            html = html,
            dataJson = "{}",
            refreshIntervalMinutes = 0,
            refreshPrompt = resources.getString(R.string.agent_space_template_daily_refresh),
        )
    }

    private fun habitTracker(resources: Resources): SpaceTemplateDraft {
        val title = resources.getString(R.string.agent_space_template_habits_title)
        val description = resources.getString(R.string.agent_space_template_habits_desc)
        val dataJson = buildJsonObject {
            putJsonArray("habits") {
                addJsonObject {
                    put("id", "read")
                    put("name", resources.getString(R.string.agent_space_template_habits_read))
                    putJsonArray("days") { repeat(7) { add(false) } }
                }
                addJsonObject {
                    put("id", "exercise")
                    put("name", resources.getString(R.string.agent_space_template_habits_exercise))
                    putJsonArray("days") { repeat(7) { add(false) } }
                }
                addJsonObject {
                    put("id", "sleep")
                    put("name", resources.getString(R.string.agent_space_template_habits_sleep))
                    putJsonArray("days") { repeat(7) { add(false) } }
                }
            }
        }.toString()
        val hint = htmlEscape(resources.getString(R.string.agent_space_template_habits_hint))
        val html = """
            <div class="m-shell">
              <div class="m-title">${htmlEscape(title)}</div>
              <div class="m-caption">$hint</div>
              <div class="m-stack" id="habit-rows" style="margin-top:12px"></div>
            </div>
            <script>
            (function(){
              var fallback = $dataJson;
              function load(){
                try {
                  var value = window.muse.getJSON ? window.muse.getJSON() : null;
                  if (value && value.habits) return value;
                } catch (e) {}
                return fallback;
              }
              var data = load();
              var host = document.getElementById('habit-rows');
              function weekDates(){
                var now = new Date();
                var monday = new Date(now);
                var shift = (now.getDay() + 6) % 7;
                monday.setDate(now.getDate() - shift);
                monday.setHours(0, 0, 0, 0);
                var out = [];
                for (var i = 0; i < 7; i++) {
                  var d = new Date(monday);
                  d.setDate(monday.getDate() + i);
                  out.push(d);
                }
                return out;
              }
              function render(){
                var week = weekDates();
                host.innerHTML = '';
                (data.habits || []).forEach(function(habit){
                  var row = document.createElement('div');
                  row.className = 'm-panel';
                  var name = document.createElement('div');
                  name.className = 'm-label';
                  name.textContent = habit.name || '';
                  row.appendChild(name);
                  var grid = document.createElement('div');
                  grid.className = 'm-flow';
                  grid.style.marginTop = '8px';
                  week.forEach(function(day, index){
                    var button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'm-tab';
                    button.textContent = String(day.getDate());
                    var done = !!(habit.days && habit.days[index]);
                    button.setAttribute('aria-selected', done ? 'true' : 'false');
                    button.addEventListener('click', function(){
                      if (!habit.days) habit.days = [false, false, false, false, false, false, false];
                      habit.days[index] = !habit.days[index];
                      render();
                    });
                    grid.appendChild(button);
                  });
                  row.appendChild(grid);
                  host.appendChild(row);
                });
              }
              render();
              if (window.muse.data && window.muse.data.onChange) {
                window.muse.data.onChange(function(next){
                  if (next && next.habits) {
                    data = next;
                    render();
                  }
                });
              }
            })();
            </script>
        """.trimIndent()
        return SpaceTemplateDraft(
            id = HABITS_ID,
            title = title,
            description = description,
            html = html,
            dataJson = dataJson,
            refreshIntervalMinutes = 0,
            refreshPrompt = resources.getString(R.string.agent_space_template_habits_refresh),
        )
    }

    private fun todoList(resources: Resources): SpaceTemplateDraft {
        val title = resources.getString(R.string.agent_space_template_todo_title)
        val description = resources.getString(R.string.agent_space_template_todo_desc)
        val placeholder = htmlEscape(resources.getString(R.string.agent_space_template_todo_placeholder))
        val addLabel = htmlEscape(resources.getString(R.string.agent_space_template_todo_add))
        val emptyLabel = jsString(resources.getString(R.string.agent_space_template_todo_empty))
        val dataJson = buildJsonObject {
            putJsonArray("items") {
                addJsonObject {
                    put("text", resources.getString(R.string.agent_space_template_todo_seed))
                    put("done", false)
                }
            }
        }.toString()
        val html = """
            <div class="m-shell">
              <div class="m-title">${htmlEscape(title)}</div>
              <div class="m-caption">${htmlEscape(description)}</div>
              <div class="m-stack" id="todo-items" style="margin-top:12px"></div>
              <div class="m-field" style="margin-top:12px">
                <input class="m-input" id="todo-input" placeholder="$placeholder">
              </div>
              <button class="m-button" id="todo-add" type="button">$addLabel</button>
            </div>
            <script>
            (function(){
              var fallback = $dataJson;
              function load(){
                try {
                  var value = window.muse.getJSON ? window.muse.getJSON() : null;
                  if (value && value.items) return value;
                } catch (e) {}
                return fallback;
              }
              var data = load();
              var list = document.getElementById('todo-items');
              function render(){
                list.innerHTML = '';
                if (!data.items || data.items.length === 0) {
                  var empty = document.createElement('div');
                  empty.className = 'm-caption';
                  empty.textContent = $emptyLabel;
                  list.appendChild(empty);
                  return;
                }
                data.items.forEach(function(item){
                  var label = document.createElement('label');
                  label.className = 'm-check';
                  var box = document.createElement('input');
                  box.type = 'checkbox';
                  box.checked = !!item.done;
                  box.addEventListener('change', function(){
                    item.done = box.checked;
                  });
                  var text = document.createElement('span');
                  text.textContent = item.text || '';
                  label.appendChild(box);
                  label.appendChild(text);
                  list.appendChild(label);
                });
              }
              function add(){
                var input = document.getElementById('todo-input');
                var text = (input.value || '').trim();
                if (!text) return;
                if (!data.items) data.items = [];
                data.items.push({text: text, done: false});
                input.value = '';
                render();
              }
              document.getElementById('todo-add').addEventListener('click', add);
              document.getElementById('todo-input').addEventListener('keydown', function(event){
                if (event.key === 'Enter') {
                  event.preventDefault();
                  add();
                }
              });
              render();
              if (window.muse.data && window.muse.data.onChange) {
                window.muse.data.onChange(function(next){
                  if (next && next.items) {
                    data = next;
                    render();
                  }
                });
              }
            })();
            </script>
        """.trimIndent()
        return SpaceTemplateDraft(
            id = TODO_ID,
            title = title,
            description = description,
            html = html,
            dataJson = dataJson,
            refreshIntervalMinutes = 0,
            refreshPrompt = resources.getString(R.string.agent_space_template_todo_refresh),
        )
    }

    private fun focusTimer(resources: Resources): SpaceTemplateDraft {
        val title = resources.getString(R.string.agent_space_template_focus_title)
        val description = resources.getString(R.string.agent_space_template_focus_desc)
        val start = resources.getString(R.string.agent_space_template_focus_start)
        val pause = jsString(resources.getString(R.string.agent_space_template_focus_pause))
        val reset = resources.getString(R.string.agent_space_template_focus_reset)
        val done = jsString(resources.getString(R.string.agent_space_template_focus_done))
        val startHtml = htmlEscape(start)
        val resetHtml = htmlEscape(reset)
        val html = """
            <div class="m-shell" style="text-align:center">
              <div class="m-eyebrow">${htmlEscape(description)}</div>
              <div class="m-title" id="focus-clock">25:00</div>
              <div class="m-caption" id="focus-status"></div>
              <div class="m-flow" style="justify-content:center;margin-top:12px">
                <button class="m-button" id="focus-start" type="button">$startHtml</button>
                <button class="m-button m-button--quiet" id="focus-reset" type="button">$resetHtml</button>
              </div>
            </div>
            <script>
            (function(){
              var remaining = 25 * 60;
              var running = false;
              var timer = null;
              var startText = ${jsString(start)};
              var pauseText = $pause;
              var doneText = $done;
              var clock = document.getElementById('focus-clock');
              var status = document.getElementById('focus-status');
              var startButton = document.getElementById('focus-start');
              function paint(){
                var minutes = Math.floor(remaining / 60);
                var seconds = remaining % 60;
                clock.textContent = (minutes < 10 ? '0' : '') + minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
                startButton.textContent = running ? pauseText : startText;
              }
              function tick(){
                if (remaining <= 0) {
                  clearInterval(timer);
                  timer = null;
                  running = false;
                  remaining = 25 * 60;
                  status.textContent = doneText;
                  paint();
                  return;
                }
                remaining -= 1;
                paint();
              }
              function toggle(){
                if (running) {
                  clearInterval(timer);
                  timer = null;
                  running = false;
                } else {
                  status.textContent = '';
                  running = true;
                  timer = setInterval(tick, 1000);
                }
                paint();
              }
              document.getElementById('focus-start').addEventListener('click', toggle);
              document.getElementById('focus-reset').addEventListener('click', function(){
                clearInterval(timer);
                timer = null;
                running = false;
                remaining = 25 * 60;
                status.textContent = '';
                paint();
              });
              paint();
            })();
            </script>
        """.trimIndent()
        return SpaceTemplateDraft(
            id = FOCUS_ID,
            title = title,
            description = description,
            html = html,
            dataJson = "{}",
            refreshIntervalMinutes = 0,
            refreshPrompt = resources.getString(R.string.agent_space_template_focus_refresh),
        )
    }

    private fun gomokuArena(resources: Resources): SpaceTemplateDraft {
        val title = resources.getString(R.string.agent_space_template_gomoku_title)
        val description = resources.getString(R.string.agent_space_template_gomoku_desc)
        val turnBlack = jsString(resources.getString(R.string.agent_space_template_gomoku_turn_black))
        val turnWhite = jsString(resources.getString(R.string.agent_space_template_gomoku_turn_white))
        val winBlack = jsString(resources.getString(R.string.agent_space_template_gomoku_win_black))
        val winWhite = jsString(resources.getString(R.string.agent_space_template_gomoku_win_white))
        val thinkingLabel = jsString(resources.getString(R.string.agent_space_template_gomoku_thinking))
        val movePrompt = jsString(resources.getString(R.string.agent_space_template_gomoku_prompt))
        val reportLabel = htmlEscape(resources.getString(R.string.agent_space_template_gomoku_report))
        val resetLabel = htmlEscape(resources.getString(R.string.agent_space_template_gomoku_reset))
        val html = """
            <div class="m-shell">
              <div class="m-title" id="gm-status"></div>
              <div class="m-caption" id="gm-comment" style="min-height:18px;margin-bottom:6px;"></div>
              <div id="gm-board" style="display:grid;grid-template-columns:repeat(11,1fr);gap:2px;width:100%;max-width:308px;aspect-ratio:1;background:rgba(128,128,128,0.16);padding:4px;border-radius:10px;margin:6px 0;"></div>
              <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:6px;">
                <button type="button" class="m-btn m-btn-primary" id="gm-report">$reportLabel</button>
                <button type="button" class="m-btn" id="gm-reset">$resetLabel</button>
              </div>
            </div>
            <script>
            (function(){
              var SIZE = 11;
              var turnBlackText = $turnBlack;
              var turnWhiteText = $turnWhite;
              var winBlackText = $winBlack;
              var winWhiteText = $winWhite;
              var thinkingText = $thinkingLabel;
              var promptPrefix = $movePrompt;
              var boardEl = document.getElementById('gm-board');
              var statusEl = document.getElementById('gm-status');
              var commentEl = document.getElementById('gm-comment');
              var state = { size: SIZE, next: 'B', moves: [], winner: '', comment: '', pendingWhite: false, __museGomokuRevision: 0 };
              var pending = false;
              // 数据回灌：进度存在宿主数据里，重挂载（悬浮窗 ↔ 侧抽屉）后必须能恢复。
              function applyState(parsed) {
                if (!parsed || !Array.isArray(parsed.moves)) return;
                // A late host push from the previous view must not rewind this mounted board.
                var incomingRevision = Number(parsed.__museGomokuRevision || 0);
                var currentRevision = Number(state.__museGomokuRevision || 0);
                if (incomingRevision < currentRevision || (incomingRevision === currentRevision && parsed.moves.length < state.moves.length)) return;
                state.moves = parsed.moves;
                state.next = parsed.next === 'W' ? 'W' : 'B';
                state.winner = parsed.winner || '';
                state.comment = parsed.comment || '';
                state.pendingWhite = parsed.pendingWhite === true;
                state.__museGomokuRevision = incomingRevision;
                render();
                recoverInterruptedWhiteTurn();
              }
              function readHostState() {
                try {
                  var parsed = null;
                  if (window.muse && typeof window.muse.getJSON === 'function') {
                    parsed = window.muse.getJSON();
                  }
                  if (!parsed && window.muse && typeof window.muse.getData === 'function') {
                    var text = window.muse.getData();
                    parsed = text ? JSON.parse(text) : null;
                  }
                  if (parsed) applyState(parsed);
                } catch (e) {}
              }
              // 订阅宿主数据：update_card_data / saveState 回写 / 推送事件到达时同步棋盘，
              // 否则悬浮窗里下的子切到侧抽屉就变成空盘。
              try {
                if (window.muse && window.muse.data && typeof window.muse.data.onChange === 'function') {
                  window.muse.data.onChange(function(next){ applyState(next); });
                }
              } catch (e) {}
              readHostState();
              // A black move is persisted before asking the model. If the view was destroyed
              // while that request was pending, recover deterministically without another ask.
              function recoverInterruptedWhiteTurn() {
                var last = state.moves.length ? state.moves[state.moves.length - 1] : null;
                if (pending || !state.pendingWhite || state.next !== 'W' || state.winner || !last || last.p !== 'B') return;
                var grid = buildGrid();
                var white = pickFallbackWhite(grid, last.r, last.c);
                if (!white) return;
                state.moves.push({ r: white.r, c: white.c, p: 'W' });
                grid[white.r][white.c] = 'W';
                if (checkWinner(grid, white.r, white.c, 'W')) state.winner = 'W';
                else state.next = 'B';
                state.comment = '';
                state.pendingWhite = false;
                saveQuietly();
                render();
              }
              function buildGrid() {
                var grid = Array.from({length: SIZE}, function(){ return Array(SIZE).fill(''); });
                for (var i = 0; i < state.moves.length; i++) {
                  var m = state.moves[i];
                  if (m && m.r >= 0 && m.r < SIZE && m.c >= 0 && m.c < SIZE) grid[m.r][m.c] = m.p;
                }
                return grid;
              }
              function saveQuietly() {
                try {
                  if (window.muse && typeof window.muse.saveState === 'function') {
                    state.__museGomokuRevision = Number(state.__museGomokuRevision || 0) + 1;
                    window.muse.saveState(state);
                  }
                } catch (e) {}
              }
              function checkWinner(grid, r, c, p) {
                var dirs = [[1,0],[0,1],[1,1],[1,-1]];
                for (var d = 0; d < dirs.length; d++) {
                  var dr = dirs[d][0], dc = dirs[d][1], count = 1;
                  for (var s = 1; s < 5; s++) {
                    var nr = r + dr*s, nc = c + dc*s;
                    if (nr < 0 || nr >= SIZE || nc < 0 || nc >= SIZE || grid[nr][nc] !== p) break;
                    count++;
                  }
                  for (var s2 = 1; s2 < 5; s2++) {
                    var pr = r - dr*s2, pc = c - dc*s2;
                    if (pr < 0 || pr >= SIZE || pc < 0 || pc >= SIZE || grid[pr][pc] !== p) break;
                    count++;
                  }
                  if (count >= 5) return true;
                }
                return false;
              }
              function pickFallbackWhite(grid, lastR, lastC) {
                var offsets = [[0,1],[1,0],[0,-1],[-1,0],[1,1],[-1,-1],[1,-1],[-1,1]];
                for (var k = 0; k < offsets.length; k++) {
                  var rr = lastR + offsets[k][0], cc = lastC + offsets[k][1];
                  if (rr >= 0 && rr < SIZE && cc >= 0 && cc < SIZE && !grid[rr][cc]) return { r: rr, c: cc };
                }
                for (var r = 0; r < SIZE; r++) {
                  for (var c = 0; c < SIZE; c++) {
                    if (!grid[r][c]) return { r: r, c: c };
                  }
                }
                return null;
              }
              async function requestSilentWhiteMove(lastR, lastC) {
                var cap = window.muse && window.muse.capabilities ? window.muse.capabilities() : null;
                if (!cap || cap.ask !== 'available' || typeof window.muse.ask !== 'function') return;
                pending = true;
                render();
                var askRevision = Number(state.__museGomokuRevision || 0);
                try {
                  var res = await window.muse.ask({
                    prompt: promptPrefix + ' (' + lastR + ',' + lastC + ')',
                    state: state,
                    responseFormat: 'json'
                  });
                  if (Number(state.__museGomokuRevision || 0) !== askRevision || state.next !== 'W') return;
                  var grid = buildGrid();
                  var chosen = null;
                  var comment = '';
                  if (res && res.ok && res.json) {
                    var rr = Number(res.json.r);
                    var cc = Number(res.json.c);
                    if (Number.isInteger(rr) && Number.isInteger(cc) && rr >= 0 && rr < SIZE && cc >= 0 && cc < SIZE && !grid[rr][cc]) {
                      chosen = { r: rr, c: cc };
                    }
                    if (typeof res.json.comment === 'string') comment = res.json.comment.trim();
                  }
                  if (!chosen) chosen = pickFallbackWhite(grid, lastR, lastC);
                  if (chosen && !state.winner) {
                    state.moves.push({ r: chosen.r, c: chosen.c, p: 'W' });
                    grid[chosen.r][chosen.c] = 'W';
                    if (checkWinner(grid, chosen.r, chosen.c, 'W')) state.winner = 'W';
                    else state.next = 'B';
                    if (comment) state.comment = comment;
                    state.pendingWhite = false;
                    saveQuietly();
                  }
                } catch (e) {
                  if (Number(state.__museGomokuRevision || 0) !== askRevision || state.next !== 'W') return;
                  var fallbackGrid = buildGrid();
                  var fb = pickFallbackWhite(fallbackGrid, lastR, lastC);
                  if (fb && !state.winner) {
                    state.moves.push({ r: fb.r, c: fb.c, p: 'W' });
                    fallbackGrid[fb.r][fb.c] = 'W';
                    if (checkWinner(fallbackGrid, fb.r, fb.c, 'W')) state.winner = 'W';
                    else state.next = 'B';
                    state.pendingWhite = false;
                    saveQuietly();
                  }
                } finally {
                  pending = false;
                  render();
                }
              }
              function render() {
                var grid = buildGrid();
                var lastMove = state.moves.length ? state.moves[state.moves.length - 1] : null;
                if (state.winner === 'B') statusEl.textContent = winBlackText;
                else if (state.winner === 'W') statusEl.textContent = winWhiteText;
                else if (pending) statusEl.textContent = thinkingText;
                else statusEl.textContent = state.next === 'B' ? turnBlackText : turnWhiteText;
                commentEl.textContent = state.comment || '';
                boardEl.innerHTML = '';
                for (var r = 0; r < SIZE; r++) {
                  for (var c = 0; c < SIZE; c++) {
                    (function(rr, cc){
                      var cell = document.createElement('button');
                      cell.type = 'button';
                      var isLast = lastMove && lastMove.r === rr && lastMove.c === cc;
                      var ring = isLast ? 'outline:2px solid var(--muse-accent);outline-offset:-1px;' : '';
                      cell.style.cssText = 'border:none;padding:0;border-radius:50%;aspect-ratio:1;display:flex;align-items:center;justify-content:center;cursor:pointer;background:' +
                        (grid[rr][cc] === 'B' ? '#1c1b1f' : (grid[rr][cc] === 'W' ? '#f8f9fa' : 'rgba(128,128,128,0.14)')) +
                        ';box-shadow:' + (grid[rr][cc] === 'W' ? 'inset 0 0 0 1px rgba(0,0,0,0.22)' : 'none') + ';' + ring;
                      cell.addEventListener('click', function(){
                        if (pending || state.winner || grid[rr][cc]) return;
                        var p = state.next;
                        state.moves.push({ r: rr, c: cc, p: p });
                        grid[rr][cc] = p;
                        if (checkWinner(grid, rr, cc, p)) {
                          state.winner = p;
                          saveQuietly();
                          render();
                          return;
                        }
                        var cap = window.muse && window.muse.capabilities ? window.muse.capabilities() : null;
                        if (p === 'B' && cap && cap.ask === 'available') {
                          state.next = 'W';
                          state.pendingWhite = true;
                          saveQuietly();
                          requestSilentWhiteMove(rr, cc);
                        } else {
                          state.next = (p === 'B') ? 'W' : 'B';
                          saveQuietly();
                          render();
                        }
                      });
                      boardEl.appendChild(cell);
                    })(r, c);
                  }
                }
              }
              document.getElementById('gm-report').addEventListener('click', function(){
                if (window.muse && typeof window.muse.send === 'function') {
                  var last = state.moves.length ? state.moves[state.moves.length - 1] : null;
                  var pos = last ? (' (' + last.r + ',' + last.c + ')') : '';
                  window.muse.send(promptPrefix + pos, JSON.stringify(state));
                }
              });
              document.getElementById('gm-reset').addEventListener('click', function(){
                if (pending) return;
                state = { size: SIZE, next: 'B', moves: [], winner: '', comment: '', pendingWhite: false,
                  __museGomokuRevision: Number(state.__museGomokuRevision || 0) };
                saveQuietly();
                render();
              });
              render();
            })();
            </script>
        """.trimIndent()
        return SpaceTemplateDraft(
            id = GOMOKU_ID,
            title = title,
            description = description,
            html = html,
            dataJson = """{"size":11,"next":"B","moves":[],"winner":"","comment":""}""",
            refreshIntervalMinutes = 0,
            refreshPrompt = resources.getString(R.string.agent_space_template_gomoku_refresh),
        )
    }
}

private fun jsString(value: String): String = AppJson.encodeToString(String.serializer(), value)

private fun htmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
