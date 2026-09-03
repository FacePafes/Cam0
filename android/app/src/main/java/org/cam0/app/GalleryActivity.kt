// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shows every photo in the user's chosen save folder
 * as a thumbnail grid, so photos taken with Cam0 can be reviewed
 * without leaving the app. Tapping a thumbnail opens [PhotoViewerActivity]
 * for a full screen, pinch zoomable view.
 *
 * Deliberately built on the plain framework GridView + BaseAdapter
 * rather than androidx.recyclerview same result for a simple grid
 * like this, no extra dependency which i do love.
 */
class GalleryActivity : ComponentActivity() {

    private lateinit var grid: GridView
    private lateinit var emptyLabel: TextView
    private lateinit var thumbnailExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // Small in memory thumbnail cache so scrolling back up doesn't
    // re decode images that were already loaded this session.
    private val thumbnailCache = LruCache<Uri, Bitmap>(64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        grid = findViewById(R.id.gallery_grid)
        emptyLabel = findViewById(R.id.gallery_empty)
        thumbnailExecutor = Executors.newFixedThreadPool(2)

        val photos = PhotoStore.listPhotos(this)
        emptyLabel.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE

        grid.adapter = PhotoAdapter(photos)
        grid.setOnItemClickListener { _, _, position, _ ->
            val entry = photos[position]
            startActivity(
                Intent(this, PhotoViewerActivity::class.java)
                    .putExtra(PhotoViewerActivity.EXTRA_PHOTO_URI, entry.uri)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailExecutor.shutdownNow()
    }

    private inner class PhotoAdapter(private val photos: List<PhotoEntry>) : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            val imageView = view.findViewById<ImageView>(R.id.photo_thumbnail)
            val entry = photos[position]

            val cached = thumbnailCache.get(entry.uri)
            if (cached != null) {
                imageView.setImageBitmap(cached)
            } else {
                imageView.setImageBitmap(null)
                imageView.tag = entry.uri
                thumbnailExecutor.execute {
                    val bmp = decodeThumbnail(entry.uri, THUMBNAIL_TARGET_PX)
                    if (bmp != null) {
                        thumbnailCache.put(entry.uri, bmp)
                        mainHandler.post {
                            // Only apply if this view hasn't been recycled
                            // for a different item in the meantime.
                            if (imageView.tag == entry.uri) {
                                imageView.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
            return view
        }
    }

    private fun decodeThumbnail(uri: Uri, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            val (w, h) = bounds.outWidth to bounds.outHeight
            while ((w / sampleSize) > targetPx * 2 || (h / sampleSize) > targetPx * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val THUMBNAIL_TARGET_PX = 200
    }
}
