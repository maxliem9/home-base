package com.homebase.android

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.homebase.android.ui.components.HbTextField
import com.homebase.android.ui.theme.HomeBaseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #659: the String overload of [HbTextField] routes its multi-line case through the TextFieldValue
 * overload, so it keeps the caret in view (#639) while still behaving like a plain String field.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp-mdpi")
class HbTextFieldTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var text by mutableStateOf("")

    private fun show(scroll: (ScrollState) -> Unit = {}) {
        composeRule.setContent {
            HomeBaseTheme {
                val s = rememberScrollState().also(scroll)
                Column(Modifier.height(300.dp).verticalScroll(s)) {
                    HbTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = false,
                        minLines = 3,
                        modifier = Modifier.testTag("field"),
                    )
                }
            }
        }
    }

    @Test
    fun `multi-line String field - typing reaches the caller and outside changes show up`() {
        text = "Hallo"
        show()
        val field = composeRule.onNodeWithTag("field")
        field.performClick()
        field.performTextInputSelection(TextRange(5))
        field.performTextInput(" Welt")
        assertEquals("Hallo Welt", text)

        text = "von außen"
        composeRule.waitForIdle()
        field.assertTextEquals("von außen")
        // caret survives the outside change (coerced into the new text) — typing still appends
        field.performTextInputSelection(TextRange(9))
        field.performTextInput("!")
        assertEquals("von außen!", text)
    }

    @Test
    fun `multi-line String field - caret at the end of a long text is scrolled into view`() {
        text = (1..40).joinToString("\n") { "Schritt $it" }
        lateinit var scroll: ScrollState
        show { scroll = it }
        val field = composeRule.onNodeWithTag("field")
        field.performClick()
        field.performTextInputSelection(TextRange(text.length))
        field.performTextInput(" fertig")
        composeRule.waitForIdle()
        assertTrue("caret at the end left off-screen (scroll=${scroll.value}/${scroll.maxValue})",
            scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 40)
    }
}
