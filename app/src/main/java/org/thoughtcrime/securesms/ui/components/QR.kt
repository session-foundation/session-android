package org.thoughtcrime.securesms.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.squareup.phrase.Phrase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import network.loki.messenger.R
import org.session.libsession.utilities.StringSubstitutionConstants.APP_NAME_KEY
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.ui.AlertDialog
import org.thoughtcrime.securesms.ui.DialogButtonData
import org.thoughtcrime.securesms.ui.GetString
import org.thoughtcrime.securesms.ui.findActivity
import org.thoughtcrime.securesms.ui.getSubbedString
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import java.util.concurrent.Executors

private const val TAG = "NewMessageFragment"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen(
        errors: Flow<String>,
        onClickSettings: () -> Unit = LocalContext.current.run { {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }.let(::startActivity)
        } },
        onScan: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LocalSoftwareKeyboardController.current?.hide()

        val context = LocalContext.current
        val permission = Manifest.permission.CAMERA
        val cameraPermissionState = rememberPermissionState(permission)

        var showCameraPermissionDialog by remember { mutableStateOf(false) }

        if (cameraPermissionState.status.isGranted) {
            ScanQrCode(errors, onScan)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(LocalDimensions.current.xlargeSpacing),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.cameraGrantAccessQr).let { txt ->
                        val c = LocalContext.current
                        Phrase.from(txt).put(APP_NAME_KEY, c.getString(R.string.app_name)).format().toString()
                    },
                    style = LocalType.current.xl,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(LocalDimensions.current.spacing))
                AccentOutlineButton(
                    stringResource(R.string.cameraGrantAccess),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // NOTE: We used to use the Accompanist's way to handle permissions in compose
                        // but it doesn't seem to offer a solution when a user manually changes a permission
                        // to 'Ask every time' form the app's settings.
                        // So we are using our custom implementation. ONE IMPORTANT THING with this approach
                        // is that we need to make sure every activity where this composable is used NEED to
                        // implement `onRequestPermissionsResult` (see LoadAccountActivity.kt for an example)
                        Permissions.with(context.findActivity())
                            .request(permission)
                            .withPermanentDenialDialog(
                                context.getSubbedString(R.string.permissionsCameraDenied,
                                    APP_NAME_KEY to context.getString(R.string.app_name))
                            ).execute()
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // camera permission denied permanently dialog
        if(showCameraPermissionDialog){
            AlertDialog(
                onDismissRequest = { showCameraPermissionDialog = false },
                title = stringResource(R.string.permissionsRequired),
                text = context.getSubbedString(R.string.permissionsCameraDenied,
                    APP_NAME_KEY to context.getString(R.string.app_name)),
                buttons = listOf(
                    DialogButtonData(
                        text = GetString(stringResource(id = R.string.sessionSettings)),
                        onClick = onClickSettings
                    ),
                    DialogButtonData(
                        GetString(stringResource(R.string.cancel))
                    )
                )
            )
        }
    }
}

@Composable
fun ScanQrCode(errors: Flow<String>, onScan: (String) -> Unit) {
    val localContext = LocalContext.current
    val cameraProvider = remember { ProcessCameraProvider.getInstance(localContext) }

    val preview = Preview.Builder().build()
    val selector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build()

    runCatching {
        cameraProvider.get().unbindAll()

        cameraProvider.get().bindToLifecycle(
            LocalLifecycleOwner.current,
            selector,
            preview,
            buildAnalysisUseCase(QRCodeReader(), onScan)
        )

    }.onFailure { Log.e(TAG, "error binding camera", it) }

    DisposableEffect(cameraProvider) {
        onDispose {
            cameraProvider.get().unbindAll()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        errors.collect { error ->
            snackbarHostState
                .takeIf { it.currentSnackbarData == null }
                ?.run {
                    scope.launch {
                        // showSnackbar() suspends until the Snackbar is dismissed.
                        // Launch in new scope so we drop new QR scan events, to prevent spamming
                        // snackbars to the user, or worse, queuing a chain of snackbars one after
                        // another to show and hide for the next minute or 2.
                        // Don't use debounce() because many QR scans can come through each second,
                        // and each scan could restart the timer which could mean no scan gets
                        // through until the user stops scanning; quite perplexing.
                        snackbarHostState.showSnackbar(message = error)
                    }
                }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(LocalDimensions.current.smallSpacing)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.padding(LocalDimensions.current.smallSpacing)
                )
            }
        }
    ) { padding ->
        Box {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { PreviewView(it).apply { preview.setSurfaceProvider(surfaceProvider) } }
            )

            Box(
                Modifier
                    .aspectRatio(1f)
                    .padding(LocalDimensions.current.spacing)
                    .clip(shape = RoundedCornerShape(26.dp))
                    .background(Color(0x33ffffff))
                    .align(Alignment.Center)
            )
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun buildAnalysisUseCase(
    scanner: QRCodeReader,
    onBarcodeScanned: (String) -> Unit
): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build().apply {
        setAnalyzer(Executors.newSingleThreadExecutor(), QRCodeAnalyzer(scanner, onBarcodeScanned))
    }

class QRCodeAnalyzer(
    private val qrCodeReader: QRCodeReader,
    private val onBarcodeScanned: (String) -> Unit
): ImageAnalysis.Analyzer {

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height

            val yPlane = image.planes[0]
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val buf = yPlane.buffer
            buf.rewind()

            val y = ByteArray(w * h)
            if (pixelStride == 1 && rowStride == w) {
                buf.get(y, 0, y.size)
            } else {
                val dup = buf.duplicate()
                var dst = 0
                for (row in 0 until h) {
                    val rowStart = row * rowStride
                    if (pixelStride == 1) {
                        dup.position(rowStart)
                        dup.get(y, dst, w)
                        dst += w
                    } else {
                        for (col in 0 until w) {
                            y[dst++] = dup.get(rowStart + col * pixelStride)
                        }
                    }
                }
            }

            // Build a source from a contiguous Y plane (no rotation)
            val base = PlanarYUVLuminanceSource(
                y, w, h, 0, 0, w, h,  false
            )

            val hints = java.util.EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            }

            val attempts = listOf(
                BinaryBitmap(HybridBinarizer(base)),
                BinaryBitmap(GlobalHistogramBinarizer(base)),
                BinaryBitmap(HybridBinarizer(com.google.zxing.InvertedLuminanceSource(base))),
                BinaryBitmap(GlobalHistogramBinarizer(com.google.zxing.InvertedLuminanceSource(base)))
            )

            for (bb in attempts) {
                try {
                    val result = qrCodeReader.decode(bb, hints)
                    onBarcodeScanned(result.text)
                    return
                } catch (_: NotFoundException) {
                    qrCodeReader.reset() // harmless, move to next attempt
                }
            }
        } catch (e: FormatException) {
            Log.e("QR", "QR decoding failed", e)
        } catch (e: ChecksumException) {
            Log.e("QR", "QR checksum exception", e)
        } catch (e: Exception) {
            Log.e("QR", "Analyzer error", e)
        } finally {
            qrCodeReader.reset()
            image.close()
        }
    }
}
