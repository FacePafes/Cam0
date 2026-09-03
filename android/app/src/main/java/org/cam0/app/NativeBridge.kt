// SPDX-License-Identifier: GPL-3.0-only
package org.cam0.app

import android.graphics.Bitmap

/**
 * Thin JNI bridge to the native GuiLite overlay. No other class in this app should touch
 * System.loadLibrary or JNI directly everything native related goes
 * through HERE ONLY!!
 */
object NativeBridge {

    init {
        System.loadLibrary("camera_overlay_jni")
        nativeInit()
    }

    /** One-time JNI setup. */
    @JvmStatic
    private external fun nativeInit()

    /** Must be called once before touch()/updateBitmap(). */
    @JvmStatic
    external fun start(width: Int, height: Int)

    /**
     * Coordinates must already be in the overlay's logical resolution.
     * Returns true if (x, y) currently lands on a control (shutter or
     * gallery button) callers use this on down events to tell a
     * button press apart from a tap elsewhere (e.g: tap to focus).
     */
    @JvmStatic
    external fun touch(x: Int, y: Int, isDown: Boolean): Boolean

    @JvmStatic
    external fun updateBitmap(bitmap: Bitmap): Boolean

    /** Visually + functionally disables the shutter button (like while saving). */
    @JvmStatic
    external fun setShutterEnabled(enabled: Boolean)

    /** Updates the top center zoom readout, 1.0f, 2.3f. */
    @JvmStatic
    external fun setZoomRatio(ratio: Float)

    /**
     * Shows a brief fading focus reticle at the given overlay-logical
     * coordinates.
     */
    @JvmStatic
    external fun showFocusReticle(x: Int, y: Int)

    /**
     * Drives time-based effects.
     * Call roughly once per rendered frame CameraOverlayView's
     * render loop already does this, nothing else needs to.
     */
    @JvmStatic
    external fun tick()

    var shutterListener: (() -> Unit)? = null

    var galleryListener: (() -> Unit)? = null

    // Called via JNI (see jni_callback.cpp) D0 NOT RENAME WITHOUT
    // updating the C++ side.
    @Suppress("unused")
    @JvmStatic
    fun onShutterPressed() {
        shutterListener?.invoke()
    }

    // Called via JNI (see jni_callback.cpp) -- D0 N0T RENAME WITH0UT
    // updating the C++ side.
    @Suppress("unused")
    @JvmStatic
    fun onGalleryPressed() {
        galleryListener?.invoke()
    }
}
