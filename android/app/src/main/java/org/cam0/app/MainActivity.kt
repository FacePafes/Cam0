// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MVP camera screen: CameraX drives a live preview (PreviewView), still
 * capture (ImageCapture), and pinch driven zoom the GuiLiteOverlayView
 * layered on top draws the shutter + gallery buttons and reports taps
 * back via NativeBridge's listeners. Captured photos are JPEG encoded
 * and saved into a single folder the user picks once via the system's
 * folder picker (Storage Access Framework tree) no broad storage
 * permission is ever requested as it doesnt need it and would be beyond stupid to ask.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: CameraOverlayView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    // Held between "capture finished" and "we've confirmed a save
    // folder is available" covers both the normal case (folder
    // already chosen) and the first run / permission lost case (folder
    // picker has to run first, then this gets saved once it returns).
    private var pendingCaptureBitmap: Bitmap? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    private val openFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                PhotoStore.persist(this, treeUri)
                val bitmap = pendingCaptureBitmap
                if (bitmap != null) {
                    pendingCaptureBitmap = null
                    saveBitmap(bitmap)
                }
            } else {
                Toast.makeText(this, R.string.folder_required, Toast.LENGTH_LONG).show()
                pendingCaptureBitmap?.recycle()
                pendingCaptureBitmap = null
                NativeBridge.setShutterEnabled(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        cameraExecutor = Executors.newSingleThreadExecutor()

        NativeBridge.shutterListener = { onShutterTapped() }
        NativeBridge.galleryListener = { openGallery() }
        overlayView.onPinchZoom = { scaleFactor -> onPinchZoom(scaleFactor) }
        overlayView.onTapToFocus = { x, y -> onTapToFocus(x, y) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (PhotoStore.savedTreeUri(this) == null) {
            promptForSaveFolder()
        }
    }

    /**
     * Explains why a folder picker is about to open before actually
     * opening it jumping straight to the system picker with no
     * context is confusing ("why is this asking me to pick a folder?").
     * Not cancelable Cam0 can't save or load anything without a
     * folder, so there's no meaningful way to dismiss this other than
     * picking one or just deleting the app.
     */
    private fun promptForSaveFolder() {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_setup_title)
            .setMessage(R.string.folder_setup_message)
            .setPositiveButton(R.string.continue_button) { _, _ -> openFolderLauncher.launch(null) }
            .setCancelable(false)
            .show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture

                // Reflects the ACTUAL applied zoom (CameraX can clamp/
                // settle it slightly differently from what onPinchZoom
                // requested), and also fires once immediately with the
                // starting 1.0x state this is the only place the zoom
                // readout gets updated from.
                camera?.cameraInfo?.zoomState?.observe(this) { state ->
                    NativeBridge.setZoomRatio(state.zoomRatio)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, R.string.camera_start_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** [scaleFactor] is a multiplier from ScaleGestureDetector, eg: 1.02 or 0.97 per update. */
    private fun onPinchZoom(scaleFactor: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val newRatio = (zoomState.zoomRatio * scaleFactor)
            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(newRatio)
    }

    /** [x]/[y] are raw view pixel coordinates within previewView's own bounds. */
    private fun onTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun onShutterTapped() {
        val capture = imageCapture ?: return
        NativeBridge.setShutterEnabled(false)
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    imageProxyToBitmap(image)
                } finally {
                    image.close()
                }
                runOnUiThread { onCaptureReady(bitmap) }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                    NativeBridge.setShutterEnabled(true)
                }
            }
        })
    }

    /** CameraX delivers JPEG compressed bytes by default, decode then apply sensor rotation. */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode captured image")

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun onCaptureReady(bitmap: Bitmap) {
        if (PhotoStore.savedTreeUri(this) == null) {
            // No folder chosen yet (or the grant was lost) ask now,
            // then save once it comes back.
            pendingCaptureBitmap = bitmap
            promptForSaveFolder()
            return
        }
        saveBitmap(bitmap)
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        cameraExecutor.execute {
            val uri: Uri? = PhotoStore.createJpegFile(this, "IMG_$timestamp.jpg")
            var success = false
            var out: OutputStream? = null
            try {
                if (uri != null) {
                    out = contentResolver.openOutputStream(uri)
                    if (out != null) {
                        // JPEG at 92, encoding is dramatically faster than
                        // PNG's lossless compression for photographic
                        // content, and 92 is high enough that the loss vs.
                        // 100 is not visually meaningful while still encoding
                        // noticeably faster than 100.
                        success = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
            } finally {
                out?.close()
                bitmap.recycle()
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (success) R.string.photo_saved else R.string.save_failed,
                    Toast.LENGTH_SHORT
                ).show()
                NativeBridge.setShutterEnabled(true)
            }
        }
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Cam0"
    }
}
