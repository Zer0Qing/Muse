package io.zer0.muse.miniphone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.MiniPhoneDb

/** 测试统一入口:每次都在内存里开一个干净的小手机库,不落盘。 */
internal fun newInMemoryMiniPhoneDb(): MiniPhoneDb {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, MiniPhoneDb::class.java)
        .allowMainThreadQueries()
        .build()
}
