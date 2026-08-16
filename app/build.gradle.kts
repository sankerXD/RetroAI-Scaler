plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retroai.scaler"
    compileSdk = 34
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.retroai.scaler"
        minSdk = 30 // Android 11+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(setOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.addAll(listOf("-std=c++17", "-O3", "-fexceptions", "-frtti", "-fopenmp", "-flto"))
                arguments.addAll(listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                ))
            }
        }
    }

    // A FIXED debug keystore, committed on purpose.
    //
    // Gradle otherwise auto-generates ~/.android/debug.keystore, which on a CI
    // runner is fresh every run: each build would carry a different signature
    // and installing a new APK over an older one fails with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. Pinning it keeps local and CI builds
    // interchangeable.
    //
    // This is a DEBUG key. It is not secret and must never be used to sign a
    // release - that needs a private keystore injected from CI secrets.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "Sanker"
            keyPassword = "android"
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
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
