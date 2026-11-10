package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FEATURE_PAGE_TAG = "mp-feature-page"
private const val FEATURE_BACK_TAG = "mp-feature-back"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneFeatureNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val profile = MpProfile(nickname = "我")

    @Test
    fun `发现页每个入口都打开注册表里对应的模块并能返回`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = profile),
                    featureContent = { feature, onBack ->
                        opened.add(feature.id)
                        Text(text = feature.id, modifier = Modifier.testTag(FEATURE_PAGE_TAG))
                        Text(
                            text = "back",
                            modifier = Modifier
                                .testTag(FEATURE_BACK_TAG)
                                .clickable(onClick = onBack),
                        )
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.DISCOVER)).performClick()
        MiniPhoneFeatureCatalog.entries.forEach { feature ->
            // 网格可滚动：模块会一直加下去，先滚到入口再点，避免排在后面的落到屏幕外
            compose.onNodeWithTag(feature.tag).performScrollTo().performClick()
            compose.onNodeWithTag(FEATURE_PAGE_TAG).assertTextEquals(feature.id)
            compose.onNodeWithTag(FEATURE_BACK_TAG).performClick()
            // 返回后回到发现页，下一个入口仍在
            compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertIsDisplayed()
        }
        assertEquals(MiniPhoneFeatureCatalog.entries.map { it.id }, opened)
    }

    @Test
    fun `打开模块时 tab 栏与页头让位给整页内容`() {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = ShellUiState(profile = profile),
                    featureContent = { feature, _ ->
                        Text(text = feature.id, modifier = Modifier.testTag(FEATURE_PAGE_TAG))
                    },
                )
            }
        }
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.DISCOVER)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneTags.MOMENTS_ENTRY).performClick()
        compose.onNodeWithTag(FEATURE_PAGE_TAG).assertTextEquals(MiniPhoneFeature.MOMENTS.id)
        compose.onNodeWithTag(MiniPhoneTags.PAGE_TITLE).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.DISCOVER)).assertDoesNotExist()
    }
}
