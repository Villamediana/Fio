package com.villamediana.novelo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("draft-test", Context.MODE_PRIVATE)
    private var now = 1_000_000L
    private fun draft() = Draft(prefs) { now }

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun whatSheWroteIsThereWhenSheComesBack() {
        draft().write("Comprar pão e ligar pro")
        now += 3 * 60 * 60_000L
        // Another Draft, as after the app was closed: the same text.
        assertEquals("Comprar pão e ligar pro", draft().read())
    }

    @Test
    fun itIsKeptADayAfterTheLastChange() {
        val d = draft()
        d.write("primeiro")
        now += 20 * 60 * 60_000L
        d.write("primeiro e segundo")
        now += 20 * 60 * 60_000L
        assertEquals("primeiro e segundo", d.read())
        now += Draft.KEEP_MS
        assertEquals("", d.read())
    }

    @Test
    fun sentOrEmptiedItIsGone() {
        val d = draft()
        d.write("algo")
        d.clear()
        assertEquals("", d.read())
        d.write("algo")
        d.write("   ")
        assertEquals("", d.read())
    }
}
