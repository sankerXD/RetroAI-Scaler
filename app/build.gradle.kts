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
        // Launchers cache the icon by package and version, so reinstalling at
        // the same version leaves the old one on the home screen no matter what
        // is in the APK. Bump this when the icon changes.
        versionCode = 2
        versionName = "1.1.0"

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

// A shader that fails to compile does not crash - the renderer degrades to a
// fully transparent overlay, which is the safety design working as intended.
// The cost is that a broken shader presents as "the enhanced picture vanished"
// with one line in logcat as the only evidence. Catch it here instead.
val checkShaders = tasks.register<Exec>("checkShaders") {
    group = "verification"
    description = "Compiles the GLSL embedded in the C++ sources."
    commandLine("python3", "${rootProject.projectDir}/tools/check_shaders.py")
}

// The offline HD-2D pipeline in the model repository has to be the same thing
// the shader ships, or every offline evaluation is measuring a pipeline nobody
// runs. Both halves of that have already failed once: 13.5 records a wide
// average validated against numpy that was a different operation on the device
// and did nothing, 13.11 records the mip read that hid the shimmer for months,
// and the shading constants had silently drifted to a 10% brightness
// difference. All three were "kept in step by hand". Skips itself when the
// model repository is not checked out alongside.
val checkShadingParity = tasks.register<Exec>("checkShadingParity") {
    group = "verification"
    description = "Checks the offline HD-2D pipeline against the shipped shader."
    commandLine("python3", "${rootProject.projectDir}/tools/check_shading_parity.py")
}

// The vector the scroll estimator returns shifts the depth the lighting is
// built from, so a wrong one does not fail loudly - it drags the shading away
// from the picture, which looks like the artefact it exists to remove. 13.3
// records the previous motion-compensation attempt here going in with the sign
// reversed and being measured twice before anyone noticed.
val checkScrollEstimator = tasks.register<Exec>("checkScrollEstimator") {
    group = "verification"
    description = "Checks the scroll estimator recovers known scrolls, sign included."
    commandLine("python3", "${rootProject.projectDir}/tools/check_scroll_estimator.py")
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(checkShaders, checkShadingParity, checkScrollEstimator) }
