package com.villamediana.fio.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.villamediana.fio.ui.components.FioIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.villamediana.fio.FioApplication
import com.villamediana.fio.ui.Nav
import com.villamediana.fio.ui.components.pressScale
import kotlinx.coroutines.launch

/** Point, tap, done: the photo goes to Fio like something said, and the app is back on the button. */
@Composable
fun CameraScreen(nav: Nav) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) nav.back() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val shutter = remember { Animatable(0f) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(flash) { capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The flash of the shutter.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shutter.value }.background(Color.White))

        Box(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
            RoundIcon(FioIcons.Close, "Fechar", Modifier.align(Alignment.TopStart)) { nav.back() }
            RoundIcon(if (flash) FioIcons.FlashOn else FioIcons.FlashOff, "Flash", Modifier.align(Alignment.TopEnd)) { flash = !flash }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("O Fio lê e guarda o que estiver na foto", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(18.dp))
            val interaction = remember { MutableInteractionSource() }
            val inner by animateFloatAsState(if (busy) 0.55f else 0.82f, tween(220), label = "shutter")
            Canvas(
                Modifier
                    .size(78.dp)
                    .pressScale(interaction, 0.9f)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null, enabled = !busy && granted) {
                        busy = true
                        scope.launch { shutter.snapTo(0.85f); shutter.animateTo(0f, tween(260)) }
                        val file = FioApplication.graph.newPhotoFile()
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    FioApplication.graph.mind.photo(file)
                                    nav.back()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    file.delete()
                                }
                            },
                        )
                    },
            ) {
                drawCircle(Color.White, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = size.minDimension / 2 * inner)
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
