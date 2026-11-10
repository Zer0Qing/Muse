package io.zer0.muse.miniphone.ui.misc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.misc.MpHolidayStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_HOLIDAY = "MiniPhoneHoliday"

/**
 * 节日与纪念日的装配点：从容器取库，把纯渲染页面接上添加/删除动作。
 *
 * 节日是全局的、不绑角色，所以装配点不需要角色列表。
 * 每年重复与只发生一次的区分、dateKey 与年份自洽都由门面保证。
 */
@Composable
internal fun MiniPhoneHolidayFeature(onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val store = remember(db) { MpHolidayStore(db) }
    val scope = rememberCoroutineScope()
    MiniPhoneHolidayScreen(
        events = store.observeAll(),
        onAdd = { dateKey, name, note, year ->
            scope.launchGuarded(TAG_HOLIDAY, "记录节日") { store.add(dateKey = dateKey, name = name, note = note, year = year) }
        },
        onDelete = { id -> scope.launchGuarded(TAG_HOLIDAY, "删除节日") { store.delete(id) } },
        onBack = onBack,
    )
}
