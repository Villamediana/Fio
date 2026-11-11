package com.villamediana.fio.data

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ToGalleryTest {
    private val dir = Files.createTempDirectory("to-gallery").toFile()

    private fun jpeg(): File = File(dir, "photo.jpg").also { f ->
        val b = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun theTurnMadeInFioGoesWithTheCopy() {
        val src = jpeg()
        val out = ToGallery.turned(src, 90, File(dir, "out.jpg"))
        assertEquals(90, ExifInterface(out.path).rotationDegrees)
        // The photo kept in Fio stays as it was.
        assertEquals(0, ExifInterface(src.path).rotationDegrees)
    }

    @Test
    fun notTurnedItIsThePhotoItself() {
        val src = jpeg()
        assertSame(src, ToGallery.turned(src, 0, File(dir, "out.jpg")))
    }
}
