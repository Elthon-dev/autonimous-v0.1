plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// sherpa-onnx Kotlin API (vendored from k2-fsa/sherpa-onnx @ v1.13.8, Apache-2.0,
// see LICENSE). Native libs are NOT committed: CI copies libsherpa-onnx-jni.so and
// libonnxruntime.so here from the v1.13.8 android release tarball.
val sherpaJniDir = File(rootProject.projectDir, "third_party/sherpa-onnx/jniLibs")

android {
    namespace = "com.k2fsa.sherpa.onnx"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Only the phone we ship to.
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets["main"].jniLibs.srcDirs(sherpaJniDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Vendored upstream code, not ours to polish.
        abortOnError = false
    }
}

dependencies {
}