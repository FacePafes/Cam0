// SPDX-License-Identifier: GPL-3.0-only
// camera_overlay.cpp
//
// Draws the camera using GuiLite's core drawing primitives directly, rather than
// GuiLite's higher level widget/dialog system, or its text/font system
//
// Pinch to zoom and tap to focus are recognized on the Android side this file only draws
// the resulting state (stuff like the zoom number), it doesn't
// do any gesture recognition itself beyond the two buttons.

#define GUILITE_ON  // required once, before including GuiLite.h.
#include "GuiLite.h"

#include "camera_overlay.h"

#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <chrono>

namespace {

inline unsigned int PIXEL_ARGB(unsigned char r, unsigned char g, unsigned char b, unsigned char a) {
    return (static_cast<unsigned int>(a) << 24) |
           (static_cast<unsigned int>(b) << 16) |
           (static_cast<unsigned int>(g) << 8) |
           static_cast<unsigned int>(r);
}

const unsigned int COLOR_TRANSPARENT = 0x00000000;

c_surface* g_surface = nullptr;
c_display* g_display = nullptr;
void* g_phy_fb = nullptr;
int g_width = 0;
int g_height = 0;

// Shutter button
int g_shutter_cx = 0;
int g_shutter_cy = 0;
int g_shutter_radius = 0;
const int SHUTTER_RING_THICKNESS = 6;
bool g_shutter_enabled = true;
bool g_shutter_pressed = false;
button_pressed_callback g_shutter_callback = nullptr;

// Gallery button
int g_gallery_left = 0;
int g_gallery_top = 0;
int g_gallery_size = 0;
bool g_gallery_pressed = false;
button_pressed_callback g_gallery_callback = nullptr;

// Zoom readout
int g_zoom_badge_cx = 0;
int g_zoom_badge_top = 0;
int g_zoom_badge_max_w = 0;
int g_zoom_badge_h = 0;
float g_zoom_ratio = 1.0f;

// Focus reticle
bool g_focus_visible = false;
int g_focus_x = 0;
int g_focus_y = 0;
std::chrono::steady_clock::time_point g_focus_shown_at;
const int FOCUS_RETICLE_DURATION_MS = 650;

// Tracks which button a touch gesture started on, so a finger
// that goes down on one button and drags onto/off the other doesn't
// misfire either one.
enum class ActiveControl { NONE, SHUTTER, GALLERY };
ActiveControl g_active_control = ActiveControl::NONE;

bool point_in_circle(int x, int y, int cx, int cy, int r) {
    const int dx = x - cx;
    const int dy = y - cy;
    return (dx * dx + dy * dy) <= (r * r);
}

bool point_in_rect(int x, int y, int left, int top, int size) {
    return x >= left && x <= (left + size) && y >= top && y <= (top + size);
}

// Fills a horizontal scanline based filled circle. GuiLite's core only
// exposes fill_rect/draw_pixel/draw_line, no native circle primitive.
void fill_circle(int cx, int cy, int r, unsigned int rgb) {
    for (int dy = -r; dy <= r; dy++) {
        const int dx = static_cast<int>(sqrt(static_cast<double>(r * r - dy * dy)));
        g_surface->fill_rect(cx - dx, cy + dy, cx + dx, cy + dy, rgb, Z_ORDER_LEVEL_0);
    }
}

// Fills a ring of the given outer radius and thickness.
void fill_ring(int cx, int cy, int r_outer, int thickness, unsigned int rgb) {
    const int r_inner = r_outer - thickness;
    for (int dy = -r_outer; dy <= r_outer; dy++) {
        const int dx_outer = static_cast<int>(sqrt(static_cast<double>(r_outer * r_outer - dy * dy)));
        if (abs(dy) <= r_inner) {
            const int dx_inner = static_cast<int>(sqrt(static_cast<double>(r_inner * r_inner - dy * dy)));
            g_surface->fill_rect(cx - dx_outer, cy + dy, cx - dx_inner, cy + dy, rgb, Z_ORDER_LEVEL_0);
            g_surface->fill_rect(cx + dx_inner, cy + dy, cx + dx_outer, cy + dy, rgb, Z_ORDER_LEVEL_0);
        } else {
            g_surface->fill_rect(cx - dx_outer, cy + dy, cx + dx_outer, cy + dy, rgb, Z_ORDER_LEVEL_0);
        }
    }
}

// Outline only rectangle, used for the
// gallery icon's two overlapping "photo" squares.
void draw_rect_outline(int left, int top, int right, int bottom, int thickness, unsigned int rgb) {
    g_surface->fill_rect(left, top, right, top + thickness - 1, rgb, Z_ORDER_LEVEL_0);
    g_surface->fill_rect(left, bottom - thickness + 1, right, bottom, rgb, Z_ORDER_LEVEL_0);
    g_surface->fill_rect(left, top, left + thickness - 1, bottom, rgb, Z_ORDER_LEVEL_0);
    g_surface->fill_rect(right - thickness + 1, top, right, bottom, rgb, Z_ORDER_LEVEL_0);
}

void redraw_shutter() {
    // Clear just the button's bounding box back to transparent, then
    // redraw, rather than the whole buffer way cheaper, and nothing else
    // overlaps this region.
    const int pad = 2;
    g_surface->fill_rect(g_shutter_cx - g_shutter_radius - pad, g_shutter_cy - g_shutter_radius - pad,
                          g_shutter_cx + g_shutter_radius + pad, g_shutter_cy + g_shutter_radius + pad,
                          COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);

    const unsigned int ring_color = PIXEL_ARGB(255, 255, 255, 255);
    fill_ring(g_shutter_cx, g_shutter_cy, g_shutter_radius, SHUTTER_RING_THICKNESS, ring_color);

    unsigned int fill_color;
    if (!g_shutter_enabled) {
        fill_color = PIXEL_ARGB(120, 120, 120, 200);
    } else if (g_shutter_pressed) {
        fill_color = PIXEL_ARGB(200, 200, 200, 255);
    } else {
        fill_color = PIXEL_ARGB(255, 255, 255, 235);
    }
    fill_circle(g_shutter_cx, g_shutter_cy, g_shutter_radius - SHUTTER_RING_THICKNESS - 4, fill_color);
}

void redraw_gallery() {
    const int pad = 2;
    g_surface->fill_rect(g_gallery_left - pad, g_gallery_top - pad,
                          g_gallery_left + g_gallery_size + pad, g_gallery_top + g_gallery_size + pad,
                          COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);

    const unsigned int alpha = g_gallery_pressed ? 255 : 210;
    const unsigned int icon_color = PIXEL_ARGB(255, 255, 255, alpha);
    const int thickness = 3;

    // Photo stack icon: two overlapping outlined squares.
    const int back_size = static_cast<int>(g_gallery_size * 0.62);
    const int back_left = g_gallery_left + (g_gallery_size - back_size) - (g_gallery_size / 8);
    const int back_top = g_gallery_top + (g_gallery_size / 10);
    draw_rect_outline(back_left, back_top, back_left + back_size, back_top + back_size, thickness, icon_color);

    const int front_size = back_size;
    const int front_left = g_gallery_left + (g_gallery_size / 10);
    const int front_top = g_gallery_top + (g_gallery_size - front_size) - (g_gallery_size / 10);
    // Clear the overlap region first so the front square reads as "on top".
    g_surface->fill_rect(front_left, front_top, front_left + front_size, front_top + front_size,
                          COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);
    draw_rect_outline(front_left, front_top, front_left + front_size, front_top + front_size, thickness, icon_color);
}

// Zoom readout
//
// GuiLite does have a real text/font system, but
// it needs a compiled LATTICE_FONT_INFO font asset generated by
// GuiLite's own font conversion tool from a TTF there's no built in
// default font in GuiLite.h itself. Pulling in a whole glyph bitmap
// font asset for a handful of digits is a lot for "2.3x",
// so this draws digits as classic seven-segment bars instead, using
// the same fill_rect primitive as everything else in this file.

struct SevenSegPattern {
    bool a, b, c, d, e, f, g; // top, top right, bottom right, bottom, bottom left, top left, middle
};

SevenSegPattern digit_pattern(int digit) {
    switch (digit) {
        case 0: return {true, true, true, true, true, true, false};
        case 1: return {false, true, true, false, false, false, false};
        case 2: return {true, true, false, true, true, false, true};
        case 3: return {true, true, true, true, false, false, true};
        case 4: return {false, true, true, false, false, true, true};
        case 5: return {true, false, true, true, false, true, true};
        case 6: return {true, false, true, true, true, true, true};
        case 7: return {true, true, true, false, false, false, false};
        case 8: return {true, true, true, true, true, true, true};
        case 9: return {true, true, true, true, false, true, true};
        default: return {false, false, false, false, false, false, false};
    }
} // cool way to do it i know

// Draws one digit within [left, top] .. [left+w, top+h]. Returns w
// for layout purposes. Stroke is deliberately thin (w/6, not a chunky w/4) as i found this alot better personally.
int draw_seven_seg_digit(int left, int top, int w, int h, int digit, unsigned int color) {
    const SevenSegPattern seg = digit_pattern(digit);
    const int thickness = w / 6 > 1 ? w / 6 : 2;
    const int mid_y = top + h / 2;

    if (seg.a) g_surface->fill_rect(left, top, left + w, top + thickness, color, Z_ORDER_LEVEL_0);
    if (seg.g) g_surface->fill_rect(left, mid_y - thickness / 2, left + w, mid_y + thickness / 2, color, Z_ORDER_LEVEL_0);
    if (seg.d) g_surface->fill_rect(left, top + h - thickness, left + w, top + h, color, Z_ORDER_LEVEL_0);
    if (seg.f) g_surface->fill_rect(left, top, left + thickness, mid_y, color, Z_ORDER_LEVEL_0);
    if (seg.b) g_surface->fill_rect(left + w - thickness, top, left + w, mid_y, color, Z_ORDER_LEVEL_0);
    if (seg.e) g_surface->fill_rect(left, mid_y, left + thickness, top + h, color, Z_ORDER_LEVEL_0);
    if (seg.c) g_surface->fill_rect(left + w - thickness, mid_y, left + w, top + h, color, Z_ORDER_LEVEL_0);

    return w;
}

// Draws a small "x"  to read as a multiplier suffix (2.3x).
void draw_x_mark(int cx, int cy, int half_size, int thickness, unsigned int color) {
    for (int i = -half_size; i <= half_size; i++) {
        g_surface->fill_rect(cx + i - thickness / 2, cy + i - thickness / 2,
                              cx + i + thickness / 2, cy + i + thickness / 2, color, Z_ORDER_LEVEL_0);
        g_surface->fill_rect(cx + i - thickness / 2, cy - i - thickness / 2,
                              cx + i + thickness / 2, cy - i + thickness / 2, color, Z_ORDER_LEVEL_0);
    }
}

void redraw_zoom_badge() {
    // Always clear the full reserved badge area first the digit
    // count can change between updates
    // ("9.9x" -> "1.0x"), so a bounding box clear sized to the
    // max possible badge avoids leaving stray pixels behind.
    g_surface->fill_rect(g_zoom_badge_cx - g_zoom_badge_max_w / 2, g_zoom_badge_top,
                          g_zoom_badge_cx + g_zoom_badge_max_w / 2, g_zoom_badge_top + g_zoom_badge_h,
                          COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);

    // Hidden at the default 1.0x only show while actually zoomed in
    // or out, as its pretty stupid to show a zoom indicator when your not zoomed in...
    const float ZOOM_HIDE_EPSILON = 0.05f;
    if (fabsf(g_zoom_ratio - 1.0f) < ZOOM_HIDE_EPSILON) {
        return;
    }

    char text[8];
    float clamped = g_zoom_ratio;
    if (clamped < 0.0f) clamped = 0.0f;
    if (clamped > 99.9f) clamped = 99.9f;
    snprintf(text, sizeof(text), "%.1f", static_cast<double>(clamped));

    // Layout: measure first (digit widths + dot + gaps + x-mark), then
    // draw centered on g_zoom_badge_cx.
    const int digit_h = g_zoom_badge_h;
    const int digit_w = digit_h * 3 / 5;
    const int gap = digit_h / 6;
    const int dot_size = digit_h / 6;
    const int x_mark_half = digit_h / 3;
    const int x_mark_thickness = digit_h / 10 > 1 ? digit_h / 10 : 2;

    int content_w = 0;
    for (const char* p = text; *p != '\0'; p++) {
        if (*p == '.') {
            content_w += dot_size + gap;
        } else {
            content_w += digit_w + gap;
        }
    }
    content_w += x_mark_half * 2; // room for the trailing x mark

    int cursor_x = g_zoom_badge_cx - content_w / 2;
    const unsigned int color = PIXEL_ARGB(255, 255, 255, 235);

    for (const char* p = text; *p != '\0'; p++) {
        if (*p == '.') {
            g_surface->fill_rect(cursor_x, g_zoom_badge_top + digit_h - dot_size,
                                  cursor_x + dot_size, g_zoom_badge_top + digit_h, color, Z_ORDER_LEVEL_0);
            cursor_x += dot_size + gap;
        } else {
            draw_seven_seg_digit(cursor_x, g_zoom_badge_top, digit_w, digit_h, *p - '0', color);
            cursor_x += digit_w + gap;
        }
    }
    draw_x_mark(cursor_x + x_mark_half, g_zoom_badge_top + digit_h / 2, x_mark_half, x_mark_thickness, color);
}

void clear_focus_reticle_area(int r) {
    const int pad = 4;
    g_surface->fill_rect(g_focus_x - r - pad, g_focus_y - r - pad,
                          g_focus_x + r + pad, g_focus_y + r + pad, COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);
}

int focus_reticle_radius() {
    return g_width / 10;
}

void draw_focus_reticle() {
    const unsigned int color = PIXEL_ARGB(255, 214, 64, 255);
    fill_ring(g_focus_x, g_focus_y, focus_reticle_radius(), 3, color);
}

} // namespace

extern "C" void start_camera_overlay(int width, int height) {
    if (g_surface != nullptr) {
        return; // already started
    }
    g_width = width;
    g_height = height;

    const int color_bytes = 4; // ARGB8888
    g_phy_fb = malloc(static_cast<size_t>(width) * height * color_bytes);
    memset(g_phy_fb, 0, static_cast<size_t>(width) * height * color_bytes);

    static c_surface surface(width, height, color_bytes, Z_ORDER_LEVEL_0);
    static c_display display(g_phy_fb, width, height, &surface, nullptr);
    g_surface = &surface;
    g_display = &display;

    // Whole buffer starts fully transparent so the camera preview
    // beneath shows through everywhere except the buttons.
    g_surface->fill_rect(0, 0, width - 1, height - 1, COLOR_TRANSPARENT, Z_ORDER_LEVEL_0);

    // Shutter button: centered horizontally, near the bottom, sized
    // relative to the logical overlay resolution so it looks right at
    // any physical screen size.
    g_shutter_radius = width / 8;
    g_shutter_cx = width / 2;
    g_shutter_cy = height - g_shutter_radius - (height / 20);

    // Gallery button: bottom left, around half the shutter's diameter,
    // vertically centered against the shutter.
    g_gallery_size = g_shutter_radius; // ~ half the shutter's diameter
    g_gallery_left = width / 12;
    g_gallery_top = g_shutter_cy - (g_gallery_size / 2);

    // Zoom readout: top center. Sized small again i think it looks better that way
    g_zoom_badge_h = height / 40;
    g_zoom_badge_top = height / 30;
    g_zoom_badge_cx = width / 2;
    g_zoom_badge_max_w = width / 2; // generous so things like "12.3x" comfortably fit

    redraw_shutter();
    redraw_gallery();
    redraw_zoom_badge();
}

extern "C" int touch_camera_overlay(int x, int y, int is_down) {
    if (g_surface == nullptr) {
        return 0;
    }
    const bool down = is_down != 0;
    const bool inside_shutter = g_shutter_enabled && point_in_circle(x, y, g_shutter_cx, g_shutter_cy, g_shutter_radius);
    const bool inside_gallery = point_in_rect(x, y, g_gallery_left, g_gallery_top, g_gallery_size);
    const int inside_any = (inside_shutter || inside_gallery) ? 1 : 0;

    if (down) {
        if (g_active_control != ActiveControl::NONE) {
            return inside_any; // a gesture is already in progress
        }
        if (inside_shutter) {
            g_active_control = ActiveControl::SHUTTER;
            g_shutter_pressed = true;
            redraw_shutter();
        } else if (inside_gallery) {
            g_active_control = ActiveControl::GALLERY;
            g_gallery_pressed = true;
            redraw_gallery();
        }
        return inside_any;
    }

    // ACTION_UP / ACTION_CANCEL: only fire if release lands back inside
    // whichever button the gesture started on.
    switch (g_active_control) {
        case ActiveControl::SHUTTER: {
            g_shutter_pressed = false;
            redraw_shutter();
            const bool inside = point_in_circle(x, y, g_shutter_cx, g_shutter_cy, g_shutter_radius);
            if (g_shutter_enabled && inside && g_shutter_callback != nullptr) {
                g_shutter_callback();
            }
            break;
        }
        case ActiveControl::GALLERY: {
            g_gallery_pressed = false;
            redraw_gallery();
            const bool inside = point_in_rect(x, y, g_gallery_left, g_gallery_top, g_gallery_size);
            if (inside && g_gallery_callback != nullptr) {
                g_gallery_callback();
            }
            break;
        }
        case ActiveControl::NONE:
        default:
            break;
    }
    g_active_control = ActiveControl::NONE;
    return inside_any;
}

extern "C" void* get_camera_overlay_fb(int* out_width, int* out_height) {
    if (out_width != nullptr) {
        *out_width = g_width;
    }
    if (out_height != nullptr) {
        *out_height = g_height;
    }
    return g_phy_fb;
}

extern "C" void set_shutter_pressed_callback(button_pressed_callback cb) {
    g_shutter_callback = cb;
}

extern "C" void set_gallery_pressed_callback(button_pressed_callback cb) {
    g_gallery_callback = cb;
}

extern "C" void set_shutter_enabled(int enabled) {
    const bool new_val = enabled != 0;
    if (new_val != g_shutter_enabled) {
        g_shutter_enabled = new_val;
        redraw_shutter();
    }
}

extern "C" void set_zoom_ratio(float ratio) {
    if (g_surface == nullptr) {
        return;
    }
    // Small dead zone so float jitter from the camera pipeline doesn't
    // trigger a redraw every frame.
    if (fabsf(ratio - g_zoom_ratio) < 0.05f) {
        return;
    }
    g_zoom_ratio = ratio;
    redraw_zoom_badge();
}

extern "C" void show_focus_reticle(int x, int y) {
    if (g_surface == nullptr) {
        return;
    }
    if (g_focus_visible) {
        clear_focus_reticle_area(focus_reticle_radius());
    }
    g_focus_x = x;
    g_focus_y = y;
    g_focus_visible = true;
    g_focus_shown_at = std::chrono::steady_clock::now();
    draw_focus_reticle();
}

extern "C" void tick_camera_overlay() {
    if (g_surface == nullptr || !g_focus_visible) {
        return;
    }
    const auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - g_focus_shown_at
    ).count();
    if (elapsed_ms >= FOCUS_RETICLE_DURATION_MS) {
        clear_focus_reticle_area(focus_reticle_radius());
        g_focus_visible = false;
    }
}
