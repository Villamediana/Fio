package com.villamediana.novelo

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Google Play build: nothing of updating itself, and only the alarm permission Play allows a reminder app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayBuildTest {
    private val asked: Set<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    }

    @Test
    fun itDoesntUpdateItself() {
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in asked)
        assertNull(Store.settingsLine)
    }

    @Test
    fun remindersUseTheAlarmSheAllows() {
        assertFalse("android.permission.USE_EXACT_ALARM" in asked)
        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in asked)
    }
}
