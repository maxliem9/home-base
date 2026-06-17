package com.homebase.android

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.homebase.android.ui.components.HbBottomNav
import com.homebase.android.ui.components.HbRoute
import com.homebase.android.ui.theme.HomeBaseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout regression for the bottom tab bar (HB-09). MainActivity lays the app out as
 * `Column { Box(weight = 1f) { activeScreen }; HbBottomNav }`. A Compose [Column] measures its
 * **non-weighted** child (the bar) FIRST, handing it the full available height as its max — so if a
 * bar element fills that height, the bar swallows the whole screen and the weighted content area
 * above it collapses to zero. That shipped once: each `HbBottomNavItem` used `Modifier.fillMaxHeight()`,
 * which rendered the bar full-screen with its icons centred and every screen blank (the
 * "centred bottom bar, nothing else" bug).
 *
 * This reproduces that exact arrangement in a fixed 800.dp-tall viewport and asserts the bar stays a
 * thin strip while the content keeps the bulk of the height. Re-adding `fillMaxHeight()` to
 * [HbBottomNavItem] flips this red (content → ~0.dp, bar → ~800.dp).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BottomNavLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bottom nav is a thin strip and leaves the content area its height`() {
        composeRule.setContent {
            HomeBaseTheme {
                // Fixed logical viewport so the bounds assertions are density-independent and
                // deterministic, regardless of the Robolectric display size.
                Box(Modifier.requiredSize(width = 400.dp, height = 800.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        // The active-screen slot, exactly as MainActivity declares it.
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("hb-content"))
                        Box(Modifier.testTag("hb-nav")) {
                            HbBottomNav(active = HbRoute.HEUTE, onSelect = {}, onMore = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val content = composeRule.onNodeWithTag("hb-content").getUnclippedBoundsInRoot()
        val nav = composeRule.onNodeWithTag("hb-nav").getUnclippedBoundsInRoot()
        val contentHeight = content.bottom - content.top
        val navHeight = nav.bottom - nav.top

        // With the bug the bar fills the whole 800.dp and the content collapses to ~0.dp.
        assertTrue(
            "content area collapsed (height=$contentHeight) — the bottom bar ate the screen",
            contentHeight > 500.dp,
        )
        assertTrue(
            "bottom bar is not a thin strip (height=$navHeight) — fillMaxHeight regression",
            navHeight < 200.dp,
        )
    }
}
