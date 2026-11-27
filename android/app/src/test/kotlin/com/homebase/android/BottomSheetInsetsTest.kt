package com.homebase.android

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.components.screenAboveBottomBar
import com.homebase.android.ui.theme.HomeBaseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #639: with edge-to-edge neither the keyboard nor the status bar resizes the window, so
 * [HbBottomSheet] must inset itself — footer flush above the IME, grip/title below the status bar,
 * and (hosted in the screen container above the bottom nav) no extra bar-height gap.
 * Fake insets are dispatched to the decor view; mdpi ⇒ 1px == 1dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class BottomSheetInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val ime = 300
    private val status = 100 // large enough that the grip+title padding alone can't clear it
    private val nav = 48
    private val footerPad = 14.dp // HbBottomSheet footer's bottom padding
    private val bar = 70.dp // stand-in bottom nav (≥ nav, it includes the nav inset like HbBottomNav)

    private fun sheet() = @Composable {
        HbBottomSheet(
            onDismiss = {},
            title = "Aufgabe",
            full = true,
            footer = { Box(Modifier.size(120.dp, 44.dp).testTag("hb-footer")) },
        ) { repeat(60) { Text("Zeile $it") } }
    }

    private fun show(imeShown: Boolean, content: @Composable () -> Unit) {
        WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
        composeRule.setContent { HomeBaseTheme { Box(Modifier.fillMaxSize().testTag("root")) { content() } } }
        composeRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (imeShown) ime else 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, status, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                .setVisible(WindowInsetsCompat.Type.ime(), imeShown)
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, insets)
        }
        composeRule.waitForIdle()
    }

    private fun footerBottom(): Dp = composeRule.onNodeWithTag("hb-footer", useUnmergedTree = true).getUnclippedBoundsInRoot().bottom
    private fun rootBottom(): Dp = composeRule.onNodeWithTag("root").getUnclippedBoundsInRoot().bottom
    private fun assertNear(msg: String, expected: Dp, actual: Dp) = assertEquals(msg, expected.value, actual.value, 1f)

    @Test
    fun `keyboard up - footer sits flush above it, title below the status bar`() {
        show(imeShown = true, sheet())
        // imePadding consumes the IME inset ⇒ the footer's navigationBarsPadding drops to 0 (no double gap)
        assertNear("footer not flush above the keyboard", rootBottom() - ime.dp - footerPad, footerBottom())
        val title = composeRule.onNodeWithText("Aufgabe").getUnclippedBoundsInRoot()
        assertTrue("title (top=${title.top}) sits under the status bar", title.top >= status.dp)
    }

    @Test
    fun `keyboard closed - footer keeps its nav-bar padding`() {
        show(imeShown = false, sheet())
        assertNear("footer lost its navigation-bar padding", rootBottom() - nav.dp - footerPad, footerBottom())
    }

    @Test
    fun `in the screen container above the bottom nav - no extra bar-height gap over the keyboard`() {
        show(imeShown = true) {
            // Mirrors MainActivity: Column { Box(weight 1, screenAboveBottomBar(bar)) { sheet }; bottom nav }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().screenAboveBottomBar(WindowInsets.navigationBars, bar)) { sheet()() }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().height(bar - nav.dp))
            }
        }
        assertNear("sheet floats above the keyboard", rootBottom() - ime.dp - footerPad, footerBottom())
    }
}
