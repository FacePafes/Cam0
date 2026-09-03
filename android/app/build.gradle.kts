// SPDX-License-Identifier: GPL-3.0-only

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.cam0.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.cam0.app"
        // CameraX's own floor.
        // CameraX itself smooths over a lot of per OEM Camera2 quirks
        // down to this level, which is most of why it's used instead
        // of raw Camera2.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // CameraX: the one dependency assides from GuiLite,
    // chosen specifically for its device/OEM quirk compatibility layer
    // over raw Camera2 so the app works across the widest range of
    // real hardware, and i cant be asked to do raw Camera2 stuff.
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Pulled in transitively by CameraX/ActivityResultContracts anyway
    // declared explicitly so the version is pinned rather than floating.
    implementation("androidx.activity:activity-ktx:1.9.2")
}
