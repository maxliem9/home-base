package com.homebase.android

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.homebase.android.ui.components.HbBottomSheet
import com.homebase.android.ui.theme.HomeBaseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout regression for the bottom-sheet footer — the sibling of [BottomNavLayoutTest] and the same
 * Compose measurement trap (#348). [HbBottomSheet] lays out its surface as
 * `Column { grip; title; scrollingContent; footer }`. A [androidx.compose.foundation.layout.Column]
 * measures its **non-weighted** children FIRST with the full remaining height — so while the content
 * column carried no `weight`, a `verticalScroll` child with long content sized itself to the sheet's
 * entire max height and the footer below it was measured with 0dp. That shipped once: the
 * "Zutaten zur Liste"-sheet's action buttons were unreachable behind the bottom nav whenever the
 * recipe had many ingredients.
 *
 * Note the guard script `scripts/check-compose-layout.sh` cannot catch this variant: the
 * height-eating child carries no `fillMaxHeight()`/`fillMaxSize()` at all, it just scrolls.
 *
 * Reverting the content column to `if (full) Modifier.weight(1f, fill = false) else Modifier`
 * flips this red (footer height → 0.dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomSheetFooterLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `sheet footer keeps its height when the content is long`() {
        composeRule.setContent {
            HomeBaseTheme {
                // Fixed logical viewport so the bounds assertions are density-independent.
                Box(Modifier.requiredSize(width = 400.dp, height = 800.dp)) {
                    HbBottomSheet(
                        onDismiss = {},
                        title = "Zutaten zur Liste",
                        // `size` (not `requiredSize`) so the footer is coerced by the height it is offered —
                        // that is exactly what collapses to 0dp when the content column eats the sheet.
                        footer = { Box(Modifier.size(120.dp, 44.dp).testTag("hb-footer")) },
                    ) {
                        // Far more rows than the sheet can show — forces the scroll column to want
                        // more than the available height.
                        repeat(60) { Text("Zutat $it") }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val footer = composeRule.onNodeWithTag("hb-footer", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val footerHeight = footer.bottom - footer.top

        // With the bug the scrolling content eats the sheet and the footer collapses to 0.dp.
        assertTrue(
            "sheet footer collapsed (height=$footerHeight) — the scrolling content ate the sheet",
            footerHeight > 40.dp,
        )
        // …and it must stay inside the 800.dp viewport, not be pushed off the bottom edge.
        assertTrue(
            "sheet footer sits below the viewport (bottom=${footer.bottom}) — hidden behind the bottom nav",
            footer.bottom <= 800.dp,
        )
    }
}
