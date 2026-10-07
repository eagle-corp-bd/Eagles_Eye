plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.eagleseye.camera"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.eagleseye.camera"
        minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures { compose = true }
    // TFLite models are mmap'd via assets.openFd() — aapt2 must store them
    // UNCOMPRESSED in the APK or the fd fails and every engine silently dies
    // (isAvailable=false → "model-unavailable" → plain camera forever).
    androidResources {
        noCompress += "tflite"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:1.5.0")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("androidx.camera:camera-video:1.5.0")
    implementation("androidx.camera:camera-compose:1.5.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    // ARCore for face mesh (optional — engine gracefully falls back if unavailable)
    implementation("com.google.ar:core:1.45.0")
    // ── MIT-licensed external integrations ──
    // 2D photo editing layer (burhanrashid52/PhotoEditor, MIT)
    implementation("com.burhanrashid52:photoeditor:1.1.4")
    // Live GL camera preview/recorder (MasayukiSuda/CameraRecorder-android, MIT)
    implementation("com.github.MasayukiSuda:CameraRecorder-android:0.1.5")
}
