plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "linuxboot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.christianzz4603.linuxboot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // PRoot ships as libproot.so / libproot-loader.so and must exist as real files in
    // nativeLibraryDir to be executable, so native libs have to be extracted at install.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// Cross-compiles the Rust runtime with cargo-ndk into src/main/jniLibs.
// Requires: rustup target aarch64-linux-android, cargo-ndk, ANDROID_NDK_HOME.
// Pass -PskipRust to build the UI without it (the app then reports the runtime as missing).
val buildRust = tasks.register<Exec>("buildRust") {
    description = "Builds the Rust runtime for arm64-v8a"
    onlyIf { !project.hasProperty("skipRust") }
    workingDir = file("../native/runtime")
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-o", "$projectDir/src/main/jniLibs", "build", "--release")
}
tasks.configureEach { if (name == "preBuild") dependsOn(buildRust) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
