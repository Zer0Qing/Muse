package io.zer0.muse.miniphone.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.launchGuarded
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

private const val TAG_MEMO = "MiniPhoneMemo"

/**
 * 备忘录：一列短条目，顶上一条输入框，写完点添加即入库。
 *
 * 空白输入不产生写入（门面已保证），这里再把输入框清空并守住重复提交：
 * 保存是异步的，按钮在写入完成前不响应第二次点击。
 */
@Composable
internal fun MiniPhoneMemoScreen(
    memoFlow: kotlinx.coroutines.flow.Flow<List<io.zer0.muse.miniphone.data.db.entity.MpMemoEntity>>,
    onSave: suspend (String) -> Unit,
    onDelete: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val rows by memoFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun commit() {
        if (saving || draft.isBlank()) return
        val text = draft
        saving = true
        scope.launchGuarded(
            TAG_MEMO,
            "保存备忘录",
            onError = { saving = false },
        ) {
            onSave(text)
            draft = ""
            saving = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneMemoTags.PAGE)
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Back,
                contentDescription = stringResource(R.string.mp_room_back),
                tint = colors.textPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(6.dp)
                    .size(22.dp)
                    .testTag(MiniPhoneMemoTags.BACK),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_memo_title),
                style = typography.body.copy(color = colors.textPrimary),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pageMargin, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.mp_memo_hint)) },
                singleLine = true,
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f).testTag(MiniPhoneMemoTags.INPUT),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_memo_add),
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = !saving) { commit() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag(MiniPhoneMemoTags.ADD),
            )
        }

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MiniPhoneMemoTags.EMPTY),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_memo_empty),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { memo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MiniPhoneMemoTags.row(memo.id))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = memo.content,
                            style = typography.body.copy(color = colors.textPrimary),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.mp_memo_delete),
                            style = typography.caption.copy(color = colors.textSecondary),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    scope.launchGuarded(TAG_MEMO, "删除备忘录") { onDelete(memo.id) }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag(MiniPhoneMemoTags.delete(memo.id)),
                        )
                    }
                }
            }
        }
    }
}
