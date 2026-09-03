// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * A SurfaceView, layered on top of everything else in the Activity, that
 * shows the GuiLite rendered stuff (see core/camera_overlay.cpp) and
 * forwards touches to it. This is the ONLY thing GuiLite draws
 * the camera preview itself is a separate PreviewView underneath,
 * driven directly by CameraX for full hardware performance :>.
 *
 * GuiLite renders into a small offscreen ARGB8888 buffer at a fixed
 * logical resolution, this view upscales that buffer with a Matrix to
 * fill the screen and downscales touch coordinates the same way.
 *
 * This view also owns all touch gesture, even for things
 * that aren't GuiLite drawn buttons, since it sits on top and receives
 * every touch first:
 * - Pinch (2 fingers) -> [onPinchZoom], routed around the native
 *   button hit testing entirely.
 * - Single finger down starting on a button -> forwarded into the
 *   native overlay for hit testing/press visuals.
 * - Single finger quick tap starting elsewhere -> treated as
 *   tap to focus: shows the native focus reticle immediately (get away with faking some responsivity yknow) and calls
 *   [onTapToFocus] with raw screen coordinates for MainActivity to
 *   actually drive CameraX's focus metering.
 */
class CameraOverlayView(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        // Logical resolution GuiLite draws into. This is chrome, not the
        // camera feed, so it can stay small and cheap to redraw, it's
        // upscaled to fill whatever the physical screen size is.
        private const val OVERLAY_WIDTH = 480
        private const val OVERLAY_HEIGHT = 960
        private const val FRAME_INTERVAL_MS = 16L // ~60fps ceiling
        private const val TAP_MAX_DURATION_MS = 300L
    }

    /** Called with a scale multiplier (eg: 1.02, 0.97) on each pinch update. */
    var onPinchZoom: ((Float) -> Unit)? = null

    /** Called with raw (view-pixel) coordinates on a tap to focus gesture. */
    var onTapToFocus: ((Float, Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private var renderThread: RenderThread? = null

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

    // Tracks whether its currently mid single finger "maybe a button
    // tap" gesture, so a pinch starting mid tap can cleanly cancel it
    // instead of leaving a button stuck in the pressed state.
    private var buttonGestureActive = false
    private var downOnButton = false
    private var lastLogicX = 0
    private var lastLogicY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTimeMs = 0L

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (buttonGestureActive) {
                    NativeBridge.touch(lastLogicX, lastLogicY, false)
                    buttonGestureActive = false
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onPinchZoom?.invoke(detector.scaleFactor)
                return true
            }
        }
    )

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // Draws this Surface above the rest of the window's content
        // (the camera PreviewView sits in the same FrameLayout below
        // this view in z order already, but SurfaceViews composite via
        // SurfaceFlinger outside the normal view hierarchy, so this is
        // what actually guarantees we end up on top).
        setZOrderOnTop(true)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        bitmap = Bitmap.createBitmap(OVERLAY_WIDTH, OVERLAY_HEIGHT, Bitmap.Config.ARGB_8888)
        NativeBridge.start(OVERLAY_WIDTH, OVERLAY_HEIGHT)
        renderThread = RenderThread(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            matrix.reset()
            matrix.postScale(width.toFloat() / OVERLAY_WIDTH, height.toFloat() / OVERLAY_HEIGHT)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.stopRunning()
        renderThread = null
        bitmap?.recycle()
        bitmap = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width
        val h = height
        if (w == 0 || h == 0) return true

        scaleGestureDetector.onTouchEvent(event)

        // Once a pinch is recognized (2+ pointers), don't also treat any
        // of those pointers as a button tap or a focus tap.
        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            if (buttonGestureActive) {
                NativeBridge.touch(lastLogicX, lastLogicY, false)
                buttonGestureActive = false
            }
            return true
        }

        val logicX = (event.x * OVERLAY_WIDTH / w).toInt()
        val logicY = (event.y * OVERLAY_HEIGHT / h).toInt()
        lastLogicX = logicX
        lastLogicY = logicY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                buttonGestureActive = true
                downOnButton = NativeBridge.touch(logicX, logicY, true)
                downRawX = event.x
                downRawY = event.y
                downTimeMs = System.currentTimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (buttonGestureActive) {
                    NativeBridge.touch(logicX, logicY, true)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (buttonGestureActive) {
                    NativeBridge.touch(logicX, logicY, false)
                    buttonGestureActive = false
                }
                if (!downOnButton) {
                    val moved = hypot((event.x - downRawX).toDouble(), (event.y - downRawY).toDouble())
                    val duration = System.currentTimeMillis() - downTimeMs
                    if (moved <= touchSlopPx && duration <= TAP_MAX_DURATION_MS) {
                        NativeBridge.showFocusReticle(logicX, logicY)
                        onTapToFocus?.invoke(event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (buttonGestureActive) {
                    NativeBridge.touch(logicX, logicY, false)
                    buttonGestureActive = false
                }
            }
        }
        return true
    }

    private inner class RenderThread(private val targetHolder: SurfaceHolder) : Thread("guilite-overlay-render") {
        @Volatile private var running = true

        fun stopRunning() {
            running = false
        }

        override fun run() {
            while (running) {
                NativeBridge.tick() // drives time based effects, like the focus reticle fade

                val bmp = bitmap
                if (bmp != null && !bmp.isRecycled && NativeBridge.updateBitmap(bmp)) {
                    val canvas: Canvas? = try {
                        targetHolder.lockCanvas()
                    } catch (e: Exception) {
                        null
                    }
                    if (canvas != null) {
                        try {
                            // Clear to fully transparent first without
                            // this, stale opaque pixels from a previous
                            // frame would build up instead of letting the
                            // camera preview show through.
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                            canvas.drawBitmap(bmp, matrix, null)
                        } finally {
                            targetHolder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                try {
                    sleep(FRAME_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    // ignore, loop checks `running`
                }
            }
        }
    }
}
