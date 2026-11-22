package org.siloserver.silo.tv.ui.screens.cast

import android.app.Application
import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "w960dp-h540dp-television")
class TvSiloCastStandbyViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backDisconnectsThenReturnsControlToUnderlyingScreen() {
        var standby by mutableStateOf<TvSiloCastReceiver.StandbyState?>(null)
        var screenBacks = 0
        var disconnects = 0
        composeRule.setContent {
            BackHandler { screenBacks++ }
            standby?.let { state ->
                TvSiloCastStandbyView(state) {
                    disconnects++
                    standby = null
                }
            }
        }

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, screenBacks)
            standby = TvSiloCastReceiver.StandbyState(controllerName = "Phone", serverName = null)
        }

        val disconnectLabel = composeRule.activity.getString(CommonR.string.cast_standby_disconnect)
        composeRule.onNodeWithText(disconnectLabel).assertIsFocused()

        composeRule.runOnIdle {
            val standbyDialog = ShadowDialog.getLatestDialog() as ComponentDialog
            if (Build.VERSION.SDK_INT < 33) {
                assertTrue(standbyDialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
                assertTrue(standbyDialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
            } else {
                standbyDialog.onBackPressedDispatcher.onBackPressed()
            }
            assertEquals(1, disconnects)
            assertEquals(1, screenBacks)
        }

        composeRule.onNodeWithText(disconnectLabel).assertDoesNotExist()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(2, screenBacks)
            assertEquals(1, disconnects)
        }
    }
}
