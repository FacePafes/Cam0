// SPDX-License-Identifier: GPL-3.0-only
// camera_overlay.h
//
// Platform independent interface to the GuiLite rendered camera
// shutter button, gallery button opens the in app photo viewer,
// a zoom ratio readout, and a brief focus reticle wherever
// the user taps to focus.
// Any platform "glue" (Android JNI, desktop test harness, etc.) only needs
// to call these functions.

#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Must be called exactly once, before any other function below.
void start_camera_overlay(int width, int height);

// Feed a touch event into the overlay
int touch_camera_overlay(int x, int y, int is_down);

// Returns a pointer to the current ARGB8888 framebuffer, and writes its
// width/height into out_width/out_height. Safe to call frequently (like
// from a render loop)
void* get_camera_overlay_fb(int* out_width, int* out_height);

// Set by the platform layer. Called by the overlay (on the thread that
// called touch_camera_overlay) when a button is tapped.
typedef void (*button_pressed_callback)(void);
void set_shutter_pressed_callback(button_pressed_callback cb);
void set_gallery_pressed_callback(button_pressed_callback cb);

// Optional: platform layer can call this to visually disable the
// shutter (e.g. while a capture is being saved) so double taps don't
// queue up multiple captures.
void set_shutter_enabled(int enabled);

// Updates the zoom ratio readout, like 2.3. Calls this
// whenever the platform's actual applied camera zoom changes.
void set_zoom_ratio(float ratio);

// Shows a brief focus at the given overlay buffer coordinates,
// for tap to focus feedback. Fades on its own.
void show_focus_reticle(int x, int y);

// Call periodically so
// time based effects like the focus reticle's fade-out actually happen.
void tick_camera_overlay();

#ifdef __cplusplus
}
#endif
