plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.dmrandevu.whisper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29

        ndk {
            // Matches the app. The native build also assumes armv8.2 half-precision, which every
            // arm64 phone this runs on has.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Optimised even in a debug build. This is a compute kernel, and at the -O0 a
                // debug variant would otherwise give it, ggml runs tens of times slower —
                // measured on the phone as ten minutes for one recognition pass that should
                // take well under one. Nobody debugs this native code from the app, and the
                // Kotlin wrapped around it stays debuggable either way.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
