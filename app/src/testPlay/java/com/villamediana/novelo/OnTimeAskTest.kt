package com.villamediana.novelo

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.villamediana.novelo.settings.ThemeMode
import com.villamediana.novelo.ui.setup.SetupScreen
import com.villamediana.novelo.ui.theme.NoveloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The Play build asks for reminders at the very minute in the first steps, on the system's own page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnTimeAskTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theFirstStepsAskForRemindersOnTime() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.setContent { NoveloTheme(ThemeMode.LIGHT) { SetupScreen(onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Lembretes na hora").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Permitir").performClick()
        compose.waitForIdle()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, opened?.action)
        assertEquals("package:" + app.packageName, opened?.dataString)
    }
}
