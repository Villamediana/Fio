package com.villamediana.novelo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.villamediana.novelo.R

/** Novelo's own icons: one thin, round-ended line for all of them (res/drawable/ic_*.xml). */
object NoveloIcons {
    val Mic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic)
    val PhotoCamera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_photo_camera)
    val FlipCamera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_flip_camera)
    val Rotate: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_rotate)
    val Play: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_play)
    val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pause)
    val Gallery: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_gallery)
    val File: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_file)
    val Upload: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_upload)
    val Tune: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_tune)
    val Back: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_back)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_search)
    val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_close)
    val More: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_more)
    val Lock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock)
    val Unlock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_unlock)
    val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_share)
    val Open: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_open)
    val Plus: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_plus)
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check)
    val Chevron: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron)
    val Sun: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sun)
    val Moon: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_moon)
    val Contrast: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_contrast)
    val Stop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)
    val Retry: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_retry)
    val Trash: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_trash)
    val Download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_download)
    val Calendar: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_calendar)
    val Bell: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_bell)
    val Steps: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_steps)
    val Pencil: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pencil)
    /** The quill, to write instead of talking (Home). */
    val Quill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_quill)
    val Send: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_send)
    val FlashOn: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_flash_on)
    val FlashOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_flash_off)
}
