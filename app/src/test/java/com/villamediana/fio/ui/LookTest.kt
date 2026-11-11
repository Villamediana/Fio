package com.villamediana.fio.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.villamediana.fio.R
import com.villamediana.fio.ui.theme.Look
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LookTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pm = context.packageManager

    private fun state(suffix: String) = pm.getComponentEnabledSetting(ComponentName(context.packageName, "com.villamediana.fio.MainActivity$suffix"))

    private fun launchers(): List<String> =
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
            .map { it.activityInfo.name.substringAfterLast('.') }

    @Test
    fun theIconFollowsTheAccentAndThereIsAlwaysOne() {
        // Out of the box: the old icon (its old name), and only it.
        assertEquals(listOf("MainActivity"), launchers())
        Look.icon(context, "blue")
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("Blue"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(""))
        assertEquals(listOf("MainActivityBlue"), launchers())
        // Back to the default accent (an empty one is the default).
        Look.icon(context, "")
        assertEquals(listOf("MainActivity"), launchers())
    }

    @Test
    fun theSplashGoesInTheAccent() {
        assertEquals(R.style.Theme_Fio_Splash_Teal, Look.splashTheme("teal"))
        assertEquals(R.style.Theme_Fio_Splash, Look.splashTheme(""))
        assertEquals(R.style.Theme_Fio_Splash, Look.splashTheme("whatever"))
    }
}
