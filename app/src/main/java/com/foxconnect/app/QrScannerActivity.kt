package com.foxconnect.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.foxconnect.app.ui.CanvasColor
import com.foxconnect.app.ui.FoxConnectTheme
import com.foxconnect.app.ui.InkColor
import com.foxconnect.app.ui.PrimaryIdleColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** In-app QR scanner: frames and credentials never leave the app process. */
class QrScannerActivity : AppCompatActivity() {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FoxConnectTheme {
                ScannerContent(onBack = { finish() }, onPreviewReady = ::bindCamera)
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { finish() }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            if (finished) return
            val plane = proxy.planes.firstOrNull() ?: return
            val luminance = packLuminance(plane, proxy.width, proxy.height)
            val source = PlanarYUVLuminanceSource(
                luminance,
                proxy.width,
                proxy.height,
                0,
                0,
                proxy.width,
                proxy.height,
                false,
            )
            val value = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            }
            reader.reset()
            if (!value.isNullOrBlank() && !finished) {
                finished = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_RAW, value))
                    finish()
                }
            }
        } finally {
            proxy.close()
        }
    }

    private fun packLuminance(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer
        buffer.rewind()
        val output = ByteArray(width * height)
        if (plane.pixelStride == 1 && plane.rowStride == width) {
            buffer.get(output, 0, minOf(output.size, buffer.remaining()))
            return output
        }
        val row = ByteArray(plane.rowStride)
        for (y in 0 until height) {
            val length = minOf(plane.rowStride, buffer.remaining())
            if (length <= 0) break
            buffer.get(row, 0, length)
            for (x in 0 until width) {
                val sourceIndex = x * plane.pixelStride
                if (sourceIndex < length) output[y * width + x] = row[sourceIndex]
            }
        }
        return output
    }

    companion object {
        const val EXTRA_QR_RAW = "com.foxconnect.extra.QR_RAW"
    }
}

@Composable
private fun ScannerContent(onBack: () -> Unit, onPreviewReady: (PreviewView) -> Unit) {
    Box(Modifier.fillMaxSize().background(CanvasColor)) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreviewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = 0.28f)) }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(248.dp)
                .border(3.dp, PrimaryIdleColor, RoundedCornerShape(24.dp)),
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = InkColor)
        }
        Text(
            stringResource(R.string.qr_next_step),
            color = InkColor,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
        )
    }
}
