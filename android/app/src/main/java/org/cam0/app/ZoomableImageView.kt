// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * A plain ImageView extended with pinch to zoom and pan, entirely with
 * framework APIs no third party zoomable image library as that would be lazy.
 *
 * Used by [PhotoViewerActivity] to let users inspect detail in a shot
 * without leaving the app.
 */
class ZoomableImageView(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 6f
    }

    private val matrix = Matrix()
    private var currentScale = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = if (currentScale == 0f) 1f else newScale / currentScale
                currentScale = newScale
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainMatrix()
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    matrix.postTranslate(dx, dy)
                    constrainMatrix()
                    imageMatrix = matrix
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPanning = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                isPanning = event.pointerCount <= 1
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    /** Loads [bitmap] and resets to a centered, fit to view scale. */
    fun setImageBitmapAndReset(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = d.intrinsicWidth.toFloat()
        val drawableHeight = d.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || drawableWidth <= 0f || drawableHeight <= 0f) return

        val fitScale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        val dx = (viewWidth - drawableWidth * fitScale) / 2f
        val dy = (viewHeight - drawableHeight * fitScale) / 2f
        matrix.postTranslate(dx, dy)
        currentScale = 1f
        imageMatrix = matrix
    }

    /** Keeps the image from being dragged/zoomed entirely off screen. */
    private fun constrainMatrix() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(rect)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        var dx = 0f
        var dy = 0f

        if (rect.width() <= viewWidth) {
            dx = (viewWidth - rect.width()) / 2f - rect.left
        } else if (rect.left > 0) {
            dx = -rect.left
        } else if (rect.right < viewWidth) {
            dx = viewWidth - rect.right
        }

        if (rect.height() <= viewHeight) {
            dy = (viewHeight - rect.height()) / 2f - rect.top
        } else if (rect.top > 0) {
            dy = -rect.top
        } else if (rect.bottom < viewHeight) {
            dy = viewHeight - rect.bottom
        }

        matrix.postTranslate(dx, dy)
    }
}
