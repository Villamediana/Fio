package com.villamediana.fio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.villamediana.fio.R

/** The few icons outside material-icons-core, as vector drawables (the whole extended set is ~30 MB of dex). */
object FioIcons {
    val Mic: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic)
    val PhotoCamera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_photo_camera)
    val Tune: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_tune)
    val FlashOn: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_flash_on)
    val FlashOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_flash_off)
}
