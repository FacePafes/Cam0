// SPDX-License-Identifier: GPL-3.0-only
// jni_bridge.cpp


#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>

#include "camera_overlay.h"

#define LOG_TAG "camera_overlay_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_start(JNIEnv*, jclass, jint width, jint height) {
    start_camera_overlay(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_cam0_app_NativeBridge_touch(JNIEnv*, jclass, jint x, jint y, jboolean isDown) {
    const int hit_control = touch_camera_overlay(x, y, isDown == JNI_TRUE ? 1 : 0);
    return hit_control != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_setZoomRatio(JNIEnv*, jclass, jfloat ratio) {
    set_zoom_ratio(ratio);
}

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_showFocusReticle(JNIEnv*, jclass, jint x, jint y) {
    show_focus_reticle(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_tick(JNIEnv*, jclass) {
    tick_camera_overlay();
}

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_setShutterEnabled(JNIEnv*, jclass, jboolean enabled) {
    set_shutter_enabled(enabled == JNI_TRUE ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_cam0_app_NativeBridge_updateBitmap(JNIEnv* env, jclass, jobject bitmap) {
    int fb_width = 0;
    int fb_height = 0;
    void* fb = get_camera_overlay_fb(&fb_width, &fb_height);
    if (fb == nullptr || fb_width <= 0 || fb_height <= 0) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap is not ARGB_8888");
        return JNI_FALSE;
    }
    if (static_cast<int>(info.width) != fb_width || static_cast<int>(info.height) != fb_height) {
        LOGE("Bitmap size %ux%u does not match overlay size %dx%d",
             info.width, info.height, fb_width, fb_height);
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    const size_t row_bytes = static_cast<size_t>(fb_width) * 4;
    const auto* src = static_cast<const uint8_t*>(fb);
    auto* dst = static_cast<uint8_t*>(pixels);
    for (int row = 0; row < fb_height; row++) {
        memcpy(dst + static_cast<size_t>(row) * info.stride, src + row * row_bytes, row_bytes);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
