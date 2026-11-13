plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The CPU types LinuxBoot runs on: 64-bit and 32-bit ARM, and 64-bit and 32-bit x86.
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
// -PrustAbis=arm64-v8a builds only one Rust target (faster local builds).
val rustAbis = (findProperty("rustAbis") as String?)?.split(",")?.map { it.trim() } ?: abis

android {
    namespace = "linuxboot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.christianzz4603.linuxboot"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0-dev"
        // No ndk.abiFilters: it cannot be combined with ABI splits. jniLibs only ever holds the four ABIs above.
    }

    // Release signing comes from the environment (CI repository secrets); never from files in the repo.
    val keystorePath = System.getenv("ALPHA_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("alpha") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("ALPHA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ALPHA_KEY_ALIAS")
                keyPassword = System.getenv("ALPHA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("alpha") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type plus a universal one, when built with -PsplitAbi (the release workflow).
    splits {
        abi {
            isEnable = project.hasProperty("splitAbi")
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    // PRoot ships as libproot.so / libproot-loader.so and must exist as real files in
    // nativeLibraryDir to be executable, so native libs have to be extracted at install.
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin") }
    }

    androidResources { localeFilters += listOf("en") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// Cross-compiles the Rust runtime with cargo-ndk into src/main/jniLibs for every ABI.
// Requires: the four rustup android targets, cargo-ndk, ANDROID_NDK_HOME.
// Pass -PskipRust to build the UI without it (the app then reports the runtime as missing).
val buildRust = tasks.register<Exec>("buildRust") {
    description = "Builds the Rust runtime for ${rustAbis.joinToString()}"
    onlyIf { !project.hasProperty("skipRust") }
    workingDir = file("../native/runtime")
    commandLine(
        listOf("cargo", "ndk") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", "$projectDir/src/main/jniLibs", "build", "--release"),
    )
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
