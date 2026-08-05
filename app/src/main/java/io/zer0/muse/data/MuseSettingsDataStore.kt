package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * P2-2：所有 Settings 子仓库共用的 `muse_settings` DataStore。
 *
 * 必须只有一个委托，否则同一文件会出现多个 DataStore 实例，
 * 在测试或并发写入时触发 IllegalStateException。
 */
internal val Context.museSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_settings")
