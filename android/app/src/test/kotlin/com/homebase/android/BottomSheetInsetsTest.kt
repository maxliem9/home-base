package com.homebase.android

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.theme.HomeBaseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #639: with edge-to-edge neither the keyboard nor the status bar resizes the window, so
 * [HbBottomSheet] must inset itself — footer above the IME, grip/title below the status bar.
 * Fake insets are dispatched to the decor view; removing `.imePadding()` / `.statusBarsPadding()`
 * from the sheet surface flips the respective assertion red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class BottomSheetInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val imePx = 300
    private val statusPx = 24

    @Test
    fun `sheet stays above the keyboard and below the status bar`() {
        WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
        composeRule.setContent {
            HomeBaseTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    HbBottomSheet(
                        onDismiss = {},
                        title = "Aufgabe",
                        full = true,
                        footer = { Box(Modifier.size(120.dp, 44.dp).testTag("hb-footer")) },
                    ) { repeat(60) { Text("Zeile $it") } }
                }
            }
        }
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
        composeRule.waitForIdle()

        val root = composeRule.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val footer = composeRule.onNodeWithTag("hb-footer", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // mdpi ⇒ 1px == 1dp
        assertTrue(
            "footer (bottom=${footer.bottom}) is drawn under the keyboard (top=${root.bottom - imePx.dp})",
            footer.bottom <= root.bottom - imePx.dp,
        )
        val title = composeRule.onNodeWithText("Aufgabe").getUnclippedBoundsInRoot()
        assertTrue("title (top=${title.top}) sits under the status bar", title.top >= statusPx.dp)
    }
}
