package io.zer0.muse.util

import android.content.Context
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareIntentHelperTest {

    private open class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }

    @Test
    fun `application context receives new task chooser and stream grant`() {
        val context = RecordingContext(ApplicationProvider.getApplicationContext())
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://muse/log.txt"))
        }

        ShareIntentHelper.startChooserSafely(context, shareIntent, "Share log")

        val chooser = context.startedIntent
        assertNotNull(chooser)
        assertTrue(chooser?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `missing share target is contained without throwing`() {
        val context = object : RecordingContext(ApplicationProvider.getApplicationContext()) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no share target")
            }
        }

        ShareIntentHelper.startChooserSafely(context, Intent(Intent.ACTION_SEND), "Share")
    }
}
