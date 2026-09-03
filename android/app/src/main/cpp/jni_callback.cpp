// SPDX-License-Identifier: GPL-3.0-only
// jni_callback.cpp

#include <jni.h>
#include <android/log.h>

#include "camera_overlay.h"

#define LOG_TAG "camera_overlay_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_java_vm = nullptr;
jclass g_native_bridge_class = nullptr;

void call_static_void_method(const char* method_name) {
    if (g_java_vm == nullptr || g_native_bridge_class == nullptr) {
        return;
    }

    JNIEnv* env = nullptr;
    bool did_attach = false;
    jint get_env_result = g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (get_env_result == JNI_EDETACHED) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return;
        }
        did_attach = true;
    } else if (get_env_result != JNI_OK) {
        LOGE("GetEnv failed: %d", get_env_result);
        return;
    }

    jmethodID method = env->GetStaticMethodID(g_native_bridge_class, method_name, "()V");
    if (method == nullptr) {
        LOGE("GetStaticMethodID(%s) failed", method_name);
        env->ExceptionClear();
    } else {
        env->CallStaticVoidMethod(g_native_bridge_class, method);
    }

    if (did_attach) {
        g_java_vm->DetachCurrentThread();
    }
}

void on_shutter_pressed_trampoline() {
    call_static_void_method("onShutterPressed");
}

void on_gallery_pressed_trampoline() {
    call_static_void_method("onGalleryPressed");
}

}

extern "C" JNIEXPORT void JNICALL
Java_org_cam0_app_NativeBridge_nativeInit(JNIEnv* env, jclass clazz) {
    env->GetJavaVM(&g_java_vm);
    g_native_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    set_shutter_pressed_callback(on_shutter_pressed_trampoline);
    set_gallery_pressed_callback(on_gallery_pressed_trampoline);
}
