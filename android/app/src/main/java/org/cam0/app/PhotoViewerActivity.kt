// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

/**
 * Full screen view of one saved photo, opened from [GalleryActivity].
 * Pinch to zoom/drag to pan (see [ZoomableImageView]); double tap to
 * reset. Decodes the image on a background thread since full resolution
 * images can be several megabytes.
 */
class PhotoViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val imageView = findViewById<ZoomableImageView>(R.id.zoomable_image)

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PHOTO_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PHOTO_URI)
        }

        if (uri == null) {
            finish()
            return
        }

        Executors.newSingleThreadExecutor().execute {
            val bitmap = try {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bitmap != null) {
                    imageView.setImageBitmapAndReset(bitmap)
                } else {
                    Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_PHOTO_URI = "photo_uri"
    }
}
