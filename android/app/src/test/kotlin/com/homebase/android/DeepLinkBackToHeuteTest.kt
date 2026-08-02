package com.homebase.android

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.homebase.android.ui.components.HbRoute
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the notification-cold-start back rule ([DeepLinkBackToHeute], #622) over a real composition:
 * after a reminder tap started the app, the *first* system back must land on „Heute" instead of
 * leaving the app — and exactly once, so the next back exits as usual.
 *
 * "Leaves the app" is observed through a fallback [OnBackPressedCallback] registered *before* the
 * composition: back callbacks are last-registered-wins, so the fallback only fires when the composed
 * handler is disabled — the stand-in for "the press fell through to the Activity/System".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepLinkBackToHeuteTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var pending by mutableStateOf(true)
    private var route by mutableStateOf(HbRoute.AUFGABEN)
    private var overlayOpen by mutableStateOf(false)

    /** How often a back press fell through the composed handler — i.e. would have left the app. */
    private var fellThrough = 0

    @Before
    fun registerFallback() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.addCallback(
                composeRule.activity,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        fellThrough++
                    }
                },
            )
        }
    }

    private fun host() = composeRule.setContent {
        DeepLinkBackToHeute(
            pending = pending,
            route = route,
            overlayOpen = overlayOpen,
            onBackToHeute = { route = HbRoute.HEUTE },
            onDone = { pending = false },
        )
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `the first back after a notification cold start lands on Heute, the second exits`() {
        host()

        pressBack()
        assertEquals("first back redirects in-app", HbRoute.HEUTE, route)
        assertEquals("first back must not leave the app", 0, fellThrough)

        // One-shot: the debt is settled, so from „Heute" back behaves normally again.
        pressBack()
        assertEquals(1, fellThrough)
        assertEquals(HbRoute.HEUTE, route)
    }

    @Test
    fun `a plain app start is never redirected`() {
        pending = false
        host()

        pressBack()
        assertEquals(1, fellThrough)
        assertEquals("no deep-link start → no in-app redirect", HbRoute.AUFGABEN, route)
    }

    @Test
    fun `an open overlay keeps its own back press`() {
        overlayOpen = true
        host()

        // Drawer/„Mehr"/Einstellungen register their handlers earlier and would lose the priority
        // race against this one — so it stands down while any of them is open.
        pressBack()
        assertEquals(1, fellThrough)
        assertEquals(HbRoute.AUFGABEN, route)

        // Overlay closed → the pending redirect is still owed and takes the next press.
        composeRule.runOnUiThread { overlayOpen = false }
        composeRule.waitForIdle()
        pressBack()
        assertEquals(HbRoute.HEUTE, route)
        assertEquals(1, fellThrough)
    }

    @Test
    fun `reaching Heute by hand settles the debt`() {
        host()

        composeRule.runOnUiThread { route = HbRoute.HEUTE }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { route = HbRoute.EINKAUF }
        composeRule.waitForIdle()

        pressBack()
        assertEquals("already been to Heute → no second redirect", HbRoute.EINKAUF, route)
        assertEquals(1, fellThrough)
    }
}
