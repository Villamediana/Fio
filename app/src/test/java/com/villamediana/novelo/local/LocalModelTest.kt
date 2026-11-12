package com.villamediana.novelo.local

import android.app.DownloadManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The ears' download: one at a time, and the switch to mobile data really switches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalModelTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun close() = scope.cancel()

    private val manager get() = context.getSystemService(DownloadManager::class.java)

    /** The downloads Android has (by id). */
    private fun queued(): List<Long> = (0L..20L).filter { id -> runCatching { shadowOf(manager).getRequest(id) }.getOrNull() != null }

    private fun model(): LocalModel {
        val m = LocalModel(context, scope)
        // The phone needs room for it (1.5 GB and a margin), or it says so and doesn't start.
        assumeTrue(File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }.usableSpace > LocalModel.PARTS.sumOf { it.size } + LocalModel.ROOM_MARGIN)
        return m
    }

    @Test
    fun twoTapsStartItOnce() = runBlocking {
        val m = model()
        withTimeout(5_000) { while (m.state.value != LocalModel.State.Missing) delay(10) }
        m.download(wifiOnly = true)
        m.download(wifiOnly = true)
        m.download()
        withTimeout(5_000) { while (queued().isEmpty()) delay(10) }
        delay(300)
        assertEquals(1, queued().size)
        // Coming from the first tap on: the screen shows it at once.
        assert(m.state.first() is LocalModel.State.Downloading)
    }

    @Test
    fun waitingForTheWifiItCanGoOnMobileData() = runBlocking {
        val m = model()
        withTimeout(5_000) { while (m.state.value != LocalModel.State.Missing) delay(10) }
        m.download(wifiOnly = true)
        withTimeout(5_000) { while (queued().isEmpty()) delay(10) }
        val first = queued().single()
        val wifiOnly = shadowOf(manager).getRequest(first)
        assertEquals(false, shadowOf(wifiOnly).allowedOverMetered)
        m.useMobileData()
        withTimeout(5_000) { while (queued().none { it != first }) delay(10) }
        val now = queued().filter { it != first }
        assertEquals(1, now.size)
        assertEquals(true, shadowOf(shadowOf(manager).getRequest(now.single())).allowedOverMetered)
    }
}
