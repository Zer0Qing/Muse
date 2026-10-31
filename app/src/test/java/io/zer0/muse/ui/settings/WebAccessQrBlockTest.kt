package io.zer0.muse.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「仅本机」模式下二维码区块的渲染规则。
 *
 * 回环地址画成二维码，别的设备扫了只会白扫一次，因此这个模式下整块不渲染（不留空框），
 * 换成一句说明；打开局域网访问后才出现二维码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebAccessQrBlockTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun localOnlyModeRendersHintInsteadOfQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = "http://127.0.0.1:8765", localOnly = true))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_web_access_local_only_hint)).assertIsDisplayed()
    }

    @Test
    fun lanModeRendersQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = "http://192.168.1.23:8765", localOnly = false))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_web_access_local_only_hint)).assertDoesNotExist()
    }

    /** 局域网开关开着但没拿到 IP：没有可编码的地址，同样整块不渲染。 */
    @Test
    fun missingUrlRendersNoQrCode() {
        compose.setContent {
            MaterialTheme {
                WebAccessQrBlock(WebAccessAddress(url = null, localOnly = false))
            }
        }

        compose.onNodeWithTag(WEB_ACCESS_QR_TAG).assertDoesNotExist()
    }
}
